package com.campustrade.common.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 客户端真实 IP 解析工具（Spring 组件，由配置驱动）。
 *
 * <h2>为什么默认不采信任何转发头</h2>
 * <p>{@code X-Forwarded-For} / {@code X-Real-IP} 是<b>请求方可以随意填写</b>的头部，不是可信事实。
 * 早期实现对这两个头部无条件采信，导致按 IP 维度的防护（登录失败锁定、注册限频）形同虚设：
 * 攻击者每次请求换一个伪造的 {@code X-Forwarded-For} 即可让计数落在任意"IP"上；
 * 反过来还可以把受害者的出口 IP 填进去，让整段真实用户被一起锁死（把限流武器化）。</p>
 *
 * <h2>现在的规则（fail-closed）</h2>
 * <ol>
 *   <li>只有 {@code request.getRemoteAddr()}（TCP 对端地址，无法被应用层伪造）属于
 *       {@code security.trusted-proxies} 中配置的代理（CIDR 或精确 IP）时，才认为该请求是
 *       <b>由我们自己的反向代理转发而来</b>，此时才去看转发头；</li>
 *   <li>解析 {@code X-Forwarded-For} 时<b>从右向左</b>取第一个"非可信地址"——右侧是离我们最近的
 *       一跳，左侧才是原始客户端；中间的每一跳如果本身也是可信代理则继续往左找，
 *       这样即使客户端伪造了最左侧的地址，伪造值也只能落在更左侧、不会被当作客户端；</li>
 *   <li>{@code security.trusted-proxies} 默认<b>为空</b>：此时一律只返回 {@code remoteAddr}，
 *       任何转发头都不参与计算（直接部署、无反向代理时为正确行为）。</li>
 * </ol>
 *
 * <p>该值只用于"提高攻击成本"的软限流与审计留痕，权威身份判定仍然只依赖数据库记录与令牌签名。</p>
 */
@Slf4j
@Component
public class ClientIpUtils {

    /** 无法解析来源时的兜底标识，避免出现 null 键或空键。 */
    public static final String UNKNOWN_IP = "unknown";

    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_X_REAL_IP = "X-Real-IP";
    private static final String UNKNOWN_LITERAL = "unknown";

    /** 配置的代理网段（CIDR 或精确 IP）。空列表 = 完全不采信转发头。 */
    private final List<IpRange> trustedProxies;

    public ClientIpUtils(@Value("${security.trusted-proxies:}") String trustedProxiesSpec) {
        this.trustedProxies = parseTrustedProxies(trustedProxiesSpec);
        if (this.trustedProxies.isEmpty()) {
            log.info("可信代理未配置（security.trusted-proxies 为空）：忽略 X-Forwarded-For / X-Real-IP，"
                    + "按 TCP 对端地址 remoteAddr 计限流维度");
        } else {
            log.info("可信代理解析已启用：仅当 remoteAddr 属于以下网段时才采信转发头，配置项数={}, 网段={}",
                    this.trustedProxies.size(), this.trustedProxies);
        }
    }

    /**
     * 解析请求来源 IP（实例方法，使用配置的可信代理列表）。
     *
     * @param request 当前请求，允许为 null（如内部调用/测试）
     * @return 来源 IP，无法解析时返回 {@link #UNKNOWN_IP}
     */
    public String resolve(HttpServletRequest request) {
        return resolve(request, trustedProxies);
    }

    /** 当前生效的可信代理网段（只读，便于诊断与测试断言）。 */
    public List<IpRange> getTrustedProxies() {
        return trustedProxies;
    }

    /** 给定地址是否属于已配置的可信代理网段。 */
    public boolean isTrustedProxy(String ip) {
        return isTrusted(ip, trustedProxies);
    }

    // =========================================================================
    // 纯函数实现（static，public 便于单元测试与诊断直接覆盖解析/匹配规则）
    // =========================================================================

    /**
     * 解析请求来源 IP（显式传入可信代理列表，便于测试与诊断）。
     *
     * @param request         当前请求，允许为 null
     * @param trustedProxies  可信代理网段（空列表 = 完全不采信转发头）
     */
    public static String resolve(HttpServletRequest request, List<IpRange> trustedProxies) {
        if (request == null) {
            return UNKNOWN_IP;
        }

        String remoteAddr = normalize(request.getRemoteAddr());

        // 关键分支：对端不是可信代理时，转发头一律不看（它们完全由客户端控制）
        if (!isTrusted(remoteAddr, trustedProxies)) {
            return StringUtils.hasText(remoteAddr) ? remoteAddr : UNKNOWN_IP;
        }

        String forwardedFor = request.getHeader(HEADER_X_FORWARDED_FOR);
        if (StringUtils.hasText(forwardedFor) && !UNKNOWN_LITERAL.equalsIgnoreCase(forwardedFor.trim())) {
            String[] hops = forwardedFor.split(",");
            // 从右向左：跳过可信代理跳，第一个非可信地址就是真实客户端
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = normalize(hops[i]);
                if (!StringUtils.hasText(hop) || UNKNOWN_LITERAL.equalsIgnoreCase(hop)) {
                    continue;
                }
                if (!isTrusted(hop, trustedProxies)) {
                    return hop;
                }
            }
            // 整条链都是可信代理（例如代理之间互相转发）：退回最左侧一跳，最接近原始客户端
            String leftmost = normalize(hops[0]);
            if (StringUtils.hasText(leftmost)) {
                return leftmost;
            }
        }

        String realIp = request.getHeader(HEADER_X_REAL_IP);
        if (StringUtils.hasText(realIp) && !UNKNOWN_LITERAL.equalsIgnoreCase(realIp.trim())) {
            return normalize(realIp);
        }

        return StringUtils.hasText(remoteAddr) ? remoteAddr : UNKNOWN_IP;
    }

    /**
     * 解析可信代理配置：逗号分隔的 CIDR 或精确 IP（IPv4 / IPv6 均可）。
     * 非法项只记警告并跳过（跳过 = 更严格，属于 fail-closed，不会因为写错格式而放开转发头）。
     */
    public static List<IpRange> parseTrustedProxies(String spec) {
        if (!StringUtils.hasText(spec)) {
            return Collections.emptyList();
        }
        List<IpRange> ranges = new ArrayList<>();
        for (String raw : spec.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            try {
                ranges.add(IpRange.parse(entry));
            } catch (IllegalArgumentException e) {
                log.warn("忽略无法解析的可信代理配置项: entry={}, reason={}", entry, e.getMessage());
            }
        }
        return Collections.unmodifiableList(ranges);
    }

    static boolean isTrusted(String ip, List<IpRange> trustedProxies) {
        if (!StringUtils.hasText(ip) || trustedProxies == null || trustedProxies.isEmpty()) {
            return false;
        }
        for (IpRange range : trustedProxies) {
            if (range.contains(ip)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 规范化单个地址：去空白、去掉 {@code [IPv6]:port} 与 {@code IPv4:port} 的端口部分。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return value;
        }
        if (value.startsWith("[")) {
            int closing = value.indexOf(']');
            if (closing > 0) {
                return value.substring(1, closing);
            }
            return value;
        }
        // IPv4:port（只有一个冒号且后半段是纯数字）时去掉端口；IPv6 含多个冒号，不做处理
        int firstColon = value.indexOf(':');
        if (firstColon > 0 && value.indexOf(':', firstColon + 1) < 0) {
            String portPart = value.substring(firstColon + 1);
            if (!portPart.isEmpty() && portPart.chars().allMatch(Character::isDigit)) {
                return value.substring(0, firstColon);
            }
        }
        return value;
    }

    /**
     * 一个可信代理网段：{@code base} 为起始地址字节，{@code prefixBits} 为前缀长度。
     */
    public record IpRange(byte[] base, int prefixBits, String literal) {

        /** 解析单个 CIDR / 精确 IP 配置项（public 便于测试与运维诊断直接验证匹配行为）。 */
        public static IpRange parse(String entry) {
            String addressPart = entry;
            int prefixBits = -1;
            int slash = entry.indexOf('/');
            if (slash >= 0) {
                addressPart = entry.substring(0, slash).trim();
                String prefixPart = entry.substring(slash + 1).trim();
                try {
                    prefixBits = Integer.parseInt(prefixPart);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("CIDR 前缀长度不是整数: " + prefixPart);
                }
            }
            if (!StringUtils.hasText(addressPart)) {
                throw new IllegalArgumentException("地址为空");
            }
            InetAddress address;
            try {
                // 只接受 IP 字面量：不做 DNS 解析（配置里出现域名说明写错了，且会引入解析不确定性）
                address = parseLiteral(addressPart);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("不是合法的 IP 字面量: " + addressPart);
            }
            byte[] bytes = canonical(address.getAddress());
            int maxBits = bytes.length * 8;
            if (prefixBits < 0) {
                prefixBits = maxBits;
            }
            if (prefixBits > maxBits) {
                throw new IllegalArgumentException("CIDR 前缀长度超出地址位宽: " + entry);
            }
            return new IpRange(bytes, prefixBits, entry.toLowerCase(Locale.ROOT));
        }

        /**
         * 判断给定地址是否落在本网段内（public 便于测试与运维诊断）。
         *
         * @param ip 待判断地址（允许带端口或 IPv6 方括号写法）
         */
        public boolean contains(String ip) {
            String normalized = normalize(ip);
            if (!StringUtils.hasText(normalized) || UNKNOWN_LITERAL.equalsIgnoreCase(normalized)) {
                return false;
            }
            byte[] candidate;
            try {
                candidate = canonical(parseLiteral(normalized).getAddress());
            } catch (UnknownHostException e) {
                return false;
            }
            if (candidate.length != base.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != base[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (base[fullBytes] & mask);
        }

        @Override
        public String toString() {
            return literal;
        }

        /** 不做 DNS 查询的 IP 字面量解析：{@code InetAddress.getByName} 对含冒号的地址不会查 DNS。 */
        private static InetAddress parseLiteral(String value) throws UnknownHostException {
            if (!value.contains(":")) {
                // IPv4：要求四段数字点分，避免 getByName 把主机名拿去解析
                String[] parts = value.split("\\.", -1);
                if (parts.length != 4) {
                    throw new UnknownHostException(value);
                }
                for (String part : parts) {
                    if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                        throw new UnknownHostException(value);
                    }
                    if (Integer.parseInt(part) > 255) {
                        throw new UnknownHostException(value);
                    }
                }
            }
            return InetAddress.getByName(value);
        }

        /** IPv4-mapped IPv6（::ffff:a.b.c.d）折叠回 4 字节，避免同一地址两种写法匹配不上。 */
        private static byte[] canonical(byte[] address) {
            if (address.length == 16) {
                boolean mapped = true;
                for (int i = 0; i < 10; i++) {
                    if (address[i] != 0) {
                        mapped = false;
                        break;
                    }
                }
                if (mapped && (address[10] & 0xFF) == 0xFF && (address[11] & 0xFF) == 0xFF) {
                    return Arrays.copyOfRange(address, 12, 16);
                }
            }
            return address;
        }
    }
}
