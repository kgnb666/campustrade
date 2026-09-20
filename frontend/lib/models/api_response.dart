import 'package:json_annotation/json_annotation.dart';

part 'api_response.g.dart';

/// 统一网络接口响应模型
@JsonSerializable(genericArgumentFactories: true)
class ApiResponse<T> {
  final int code;
  final String message;
  final T? data;
  final int? timestamp;

  ApiResponse({
    required this.code,
    required this.message,
    this.data,
    this.timestamp,
  });

  bool get isSuccess => code == 200;

  factory ApiResponse.fromJson(
    Map<String, dynamic> json,
    T Function(Object? json) fromJsonT,
  ) =>
      _$ApiResponseFromJson(json, fromJsonT);

  Map<String, dynamic> toJson(Object? Function(T value) toJsonT) =>
      _$ApiResponseToJson(this, toJsonT);
}
