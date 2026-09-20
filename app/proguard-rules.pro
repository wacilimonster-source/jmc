# 哔咔/禁漫协议层混淆保留规则（M2 阶段启用）
# -keep class com.pika.core.** { *; }

# BouncyCastle（自定义 TLS，绕过 Cloudflare BoringSSL 拦截）
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
