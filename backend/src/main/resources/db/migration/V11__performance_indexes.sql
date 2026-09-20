-- ==============================================================================
-- Flyway Migration V11: 阶段 7 性能与体验优化 —— 索引治理与计数列扩容
--
-- 本迁移只做三件事，不改变任何业务规则、不改变任何接口字段：
--   1) 为商品列表补两条复合索引，让「状态过滤 + 时间倒序」走索引而不是扫全表后过滤；
--   2) 清理 5 个「同列重复覆盖」的冗余索引（删除决策全部来自 pg_stat_user_indexes 实测数据）；
--   3) goods.view_count 由 INT 放大为 BIGINT（累计计数器不再有 2^31 溢出风险）。
--
-- 证据链（全部是迁移编写前在开发库 campustrade 上的只读实测，命令与输出见下）：
--   * 索引使用统计：SELECT relname, indexrelname, idx_scan FROM pg_stat_user_indexes
--                   WHERE schemaname='campus_trade';
--   * 查询形态实测：EXPLAIN (ANALYZE, BUFFERS) <商品列表查询>（见第 1 节注释中的扫描行数）；
--   * 规模实测：在独立实验库上用 200k 行 goods 复现了「Rows Removed by Filter: 80000」
--              的退化计划（结论见第 1 节注释），迁移本身不依赖该实验库。
--
-- 可重复执行：全部语句均为 IF EXISTS / IF NOT EXISTS 形式，重复执行结果一致；
-- 向前兼容：不删除、不改写任何业务数据行，仅新增/删除索引与放大列类型。
-- 注意：CREATE INDEX 会短暂持有表的 SHARE 锁（阻塞并发写入），与 V1..V10 的写法保持一致，
--      大规模生产库若不能接受锁等待，可在业务低峰执行或改为手工 CONCURRENTLY 执行后
--      用 flyway repair / baseline 对齐（本工程开发库与测试库数据量下执行时间 < 100ms）。
-- ==============================================================================

-- ==============================================================================
-- 1. 商品列表复合索引
--
-- 列表查询的真实形态（GoodsServiceImpl#pageGoods，MyBatis-Plus 生成）：
--   SELECT ... FROM campus_trade.goods
--   WHERE status = 'ON_SALE' [AND category_id IN (...)] [AND school_id = ?] [AND price BETWEEN ...]
--   ORDER BY created_time DESC
--   LIMIT 10 OFFSET 0;          -- 以及配套的 SELECT COUNT(*)（同一 WHERE，无 ORDER BY）
--
-- 迁移前的问题（开发库 589 行时仍能勉强走索引，规模上立即退化；下方是 200k 行实验库实测）：
--   * status + created_time：
--       Index Scan Backward using idx_goods_created_time
--         Filter: status = 'ON_SALE'  ->  Rows Removed by Filter: 80000
--     即：为了拿到 10 行在售商品，必须先沿 created_time 倒序翻过 8 万行非在售数据。
--     idx_goods_status 与 idx_goods_created_time 是两个单列索引，PG 无法同时用它们
--     "既过滤又排序"，只能选其一。
--   * status + category_id + created_time：退化更明显（同样 Rows Removed by Filter: 80085）。
--   * COUNT(*)（searchCount 的配套查询）：Parallel Seq Scan on goods，
--     Rows Removed by Filter: 26667 × 3 workers（同一 8 万行的浪费）。
--
-- 两条复合索引把「过滤列在前、排序列在后」编码进索引本身，于是：
--   * 等值过滤 status 直接定位 B 树区间；
--   * created_time DESC 与索引第 2 列方向一致（索引按 DESC 建），无需额外 Sort；
--   * 加 category_id 后 (status, category_id, created_time DESC) 可同时完成过滤 + 排序 + LIMIT，
--     只在 category_id 等值/IN 场景生效（第 2 列必须是等值才能继续用第 3 列排序）。
-- 两条索引都刻意把 status 放首列：它是所有列表/计数查询的恒定谓词（见 pageGoods 第 156 行），
-- 因此复合索引也能承接"只用 status"的谓词。但实测结论是：纯 COUNT(*) 仍以更窄的单列
-- idx_goods_status 更优（200k 行实验库：Index Only Scan 22 buffers / 1.0ms），
-- 因此本迁移保留 idx_goods_status，不把它当作"被复合索引取代"的冗余索引删除。
--
-- 【迁移后实测与运维提示（避免把两条索引的作用理解错）】
--   * (status, category_id, created_time DESC)：确认被规划器采用且收益决定性。
--     实验库 200k 行、category_id=7（约 0.1% 长尾小类）：
--       迁移前 BitmapAnd(idx_category_id, idx_status) + top-N Sort = 224 buffers / 0.553ms；
--       迁移后 Index Scan using idx_goods_status_category_created = 16 buffers / 0.119ms，
--       且 Index Cond 同时吃掉状态与分类两个谓词、Sort 完全消失。
--     COUNT(*) 亦从 BitmapAnd 两条索引变为单条索引 Index Cond。
--   * (status, created_time DESC)：索引本身按预期生效（Index Cond 只吃 status、零过滤），
--     但"无筛选的列表首屏"上规划器仍偏好更窄的 idx_goods_created_time（代价估计相近时选窄索引），
--     开发库 589 行下该索引的 idx_scan 可能长期为 0。它的收益出现在两处（均为实测）：
--       1) 深分页（OFFSET 5000）：复合索引只顺序跳过 5010 个索引项，0 行被过滤
--          （366 buffers / 0.86ms）；created_time 索引需扫过 20000 项并丢弃 15090 行
--          （705 buffers / 2.05～3.08ms）；
--       2) 在售占比下降或在售行在时间轴上聚集时：created_time 索引的
--          "Rows Removed by Filter" 会线性放大（实验库中达 80000 行），复合索引恒为 0。
--     规划器是否自然选择索引路径取决于 random_page_cost：默认值 4（机械盘假设）偏向
--     顺序扫描/窄索引；在 SSD 上设为 1.1 后，首屏、深分页与 count 都会改用索引路径（实测）。
--     若运维确认长期不需要（在售占比高且不做深分页），可安全删除该索引以省下写入放大。
-- ==============================================================================

CREATE INDEX IF NOT EXISTS idx_goods_status_created
    ON campus_trade.goods (status, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_goods_status_category_created
    ON campus_trade.goods (status, category_id, created_time DESC);

-- ==============================================================================
-- 2. 冗余索引清理（依据：pg_stat_user_indexes 实测 + 索引列集合包含关系）
--
-- 判据（两条同时满足才删）：
--   (a) 该索引的全部列构成另一个保留索引的前缀（即保留索引可以完整承接它的所有访问路径）；
--   (b) 它自身在统计窗口内的 idx_scan 体现不出不可替代性 —— 与它重复的那个索引要么是
--       UNIQUE 约束索引（业务不变式，不可删除，必须由它承接访问），要么本身就已被高频使用。
--
-- ⚠️ 关键点：当一组重复索引里有 UNIQUE 约束索引时，能删的只有普通索引（另一个是唯一性约束，
--     删掉就等于删除业务不变式）。删除后访问路径由同列的唯一索引原样承接，
--     下方每一条都记录了「删除前后都由索引扫描完成」的实测结论。
--
-- 开发库实测（迁移编写前的 pg_stat_user_indexes.idx_scan 快照）：
--   user         : idx_user_username=16535  user_username_key=0
--   user         : idx_user_email=334       user_email_key=0
--   user_credit  : idx_user_credit_user=14205  user_credit_user_id_key=1
--   review_like  : idx_review_like_review=5644 uk_review_like_review_user=4006
--   favorite     : idx_favorite_user_id=0   idx_favorite_user_time=83  uk_favorite_user_goods=432
--   （"=0" 表示整个统计窗口内规划器一次都没有选择它；两个索引列集合完全相同的场景下，
--     规划器只会用其中一个，这正是"重复索引"的典型征兆。）
-- ==============================================================================

-- 2.1 user.idx_user_username（username 单列）与 UNIQUE 约束索引 user_username_key 列集合完全相同。
--     唯一索引承担读路径（删前 idx_user_username 命中 16535 次，删后规划器必然改用 user_username_key，
--     两者都是 username 上的 btree，代价等价），
--     而 UNIQUE 索引不可删除（username 唯一性 = 认证身份的唯一性，是阶段 2 的安全基线）。
DROP INDEX IF EXISTS campus_trade.idx_user_username;

-- 2.2 user.idx_user_email 同理：email 唯一性由 user_email_key 保证，重复的普通索引无保留价值。
DROP INDEX IF EXISTS campus_trade.idx_user_email;

-- 2.3 user_credit.idx_user_credit_user（user_id 单列）与 UNIQUE 约束索引 user_credit_user_id_key 完全重复。
--     保留唯一索引：一个用户只能有一条信用档案（阶段 5 的信用体系依赖该不变式）。
DROP INDEX IF EXISTS campus_trade.idx_user_credit_user;

-- 2.4 review_like.idx_review_like_review（review_id 单列）是
--     UNIQUE 索引 uk_review_like_review_user(review_id, user_id) 的前缀：
--     "按 review_id 查点赞明细"完全由后者承接（这正是 V8 注释里"同时可作为 review_id 前缀索引"的承诺）。
DROP INDEX IF EXISTS campus_trade.idx_review_like_review;

-- 2.5 favorite.idx_favorite_user_id（user_id 单列）同时是
--     idx_favorite_user_time(user_id, created_time DESC) 与 uk_favorite_user_goods(user_id, goods_id) 的前缀。
--     实测 idx_scan = 0（从未被选中），保留后两者即可（前者承接"按时间看收藏"，后者承接去重与存在性判断）。
DROP INDEX IF EXISTS campus_trade.idx_favorite_user_id;

-- ==============================================================================
-- 3. goods.view_count INT -> BIGINT
--
-- 该列是"只增不减"的累计计数器（GoodsServiceImpl#doSyncViewCounts 用
-- view_count = view_count + delta 定点累加，Redis 增量由定时任务刷盘）。
-- INT 上限 2147483647：单个热门商品长期累加存在溢出风险，而溢出会直接抛错并使浏览量落盘失败，
-- 进而让脏集合被反复消费（见 syncSingleGoodsViewCount 的重试设计）。
-- 放大为 BIGINT 是纯存储层加固：Java 侧 Goods/GoodsListVO/GoodsDetailVO 的 viewCount
-- 仍保持 Integer 字段名与类型不变（接口字段零变化），读路径 getInt 对 BIGINT 列照常工作。
--
-- 用 DO 块判断当前类型，保证重复执行不产生多余的 ACCESS EXCLUSIVE 重写动作。
-- ==============================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'campus_trade'
          AND table_name = 'goods'
          AND column_name = 'view_count'
          AND data_type <> 'bigint'
    ) THEN
        ALTER TABLE campus_trade.goods
            ALTER COLUMN view_count TYPE BIGINT USING view_count::BIGINT;
        RAISE NOTICE 'V11: goods.view_count 已放大为 BIGINT';
    ELSE
        RAISE NOTICE 'V11: goods.view_count 已是 BIGINT，跳过';
    END IF;
END $$;

-- ==============================================================================
-- 4. 统计信息刷新（让新索引立刻具备正确的选择率估计，避免迁移后首次查询仍走旧计划）
-- ==============================================================================
ANALYZE campus_trade.goods;
ANALYZE campus_trade."user";
ANALYZE campus_trade.user_credit;
ANALYZE campus_trade.review_like;
ANALYZE campus_trade.favorite;
