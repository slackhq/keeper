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
-keep class okio.ByteString {
  public java.lang.String hex();
  okio.ByteString$Companion Companion;
}
-keep class okio.ByteString$Companion {
  public okio.ByteString encodeUtf8(java.lang.String);
}
