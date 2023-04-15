-keep class com.slack.keeper.example.a.AClass {
  public static void sampleMethod();
}
-keep class com.slack.keeper.example.c.TestOnlyCClass {
  public static void sampleMethod();
}
-keep class com.slack.keeper.sample.TestOnlyClass {
  public static void testOnlyMethod();
}
-keep class com.slack.keeper.sample.TestOnlyKotlinClass {
  public void testOnlyMethod();
  com.slack.keeper.sample.TestOnlyKotlinClass INSTANCE;
}
-keep interface kotlin.Lazy {
  public java.lang.Object getValue();
}
-keep class kotlin.LazyKt {
}
-keep class kotlin.LazyKt__LazyJVMKt {
  public static kotlin.Lazy lazy(kotlin.jvm.functions.Function0);
}
-keep class kotlin.Unit {
  kotlin.Unit INSTANCE;
}
-keep class kotlin.io.CloseableKt {
  public static void closeFinally(java.io.Closeable,java.lang.Throwable);
}
-keep interface kotlin.jvm.functions.Function0 {
  public java.lang.Object invoke();
}
-keep class kotlin.jvm.internal.Intrinsics {
  public static boolean areEqual(java.lang.Object,java.lang.Object);
  public static void checkNotNull(java.lang.Object);
  public static void checkNotNull(java.lang.Object,java.lang.String);
  public static void checkNotNullExpressionValue(java.lang.Object,java.lang.String);
  public static void checkNotNullParameter(java.lang.Object,java.lang.String);
}
-keep class kotlin.jvm.internal.Lambda {
  public <init>(int);
}
-keep class kotlin.jvm.internal.StringCompanionObject {
  kotlin.jvm.internal.StringCompanionObject INSTANCE;
}
-keep class okio.ByteString {
  public java.lang.String hex();
  okio.ByteString$Companion Companion;
}
-keep class okio.ByteString$Companion {
  public okio.ByteString encodeUtf8(java.lang.String);
}
-keeppackagenames kotlin
