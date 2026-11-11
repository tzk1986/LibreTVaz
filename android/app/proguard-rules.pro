# P0-2（审计 2026-11-11）：R8 实崩补规则——Room 注解生成的 DB 实现类（WorkManager 的 WorkDatabase_Impl、
# media3 SimpleCache 的缓存 DB）按反射 Class.getDeclaredConstructor 实例化，R8 剥无参构造即冷启崩溃。
-keep class * extends androidx.room.RoomDatabase { <init>(); }

