MiniMax 协议层离线验证脚本
==========================
用 JVM 直接跑 com.marvis.signin.net.Minimax 的解析与请求逻辑，不需要 Android 环境。

准备：
  1) JDK 17
  2) org.json 依赖：https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
  3) 本目录下的 stub/android/util/Base64.java（Android Base64 的最小替身）

编译与运行：
  SRC=<本工程>/java
  javac -encoding UTF-8 -nowarn -cp json.jar -d classes \
      验证/stub/android/util/Base64.java \
      $SRC/com/marvis/signin/util/Http.java \
      $SRC/com/marvis/signin/net/Minimax.java \
      验证/TestMinimax.java 验证/TestLive.java
  java -cp .:json.jar:classes TestMinimax    # 14 项：JSON 判定 + PKCE S256（RFC 7636 官方向量）
  java -cp .:json.jar:classes TestLive       # 端到端打真实接口：设备码、pending、401 路径

注：TestLive 会对 TLS 放开校验，因为沙箱出网经过代理；真机直连官方证书链不受影响。

百度搭子协议层验证脚本
==========================
验证 Cookie 识别、csrfToken 解码、桌面端请求头、域名白名单、响应解析，
并打一次真实接口的假 Cookie 401 路径（不会接触真实账号）。

编译与运行：
  javac -encoding UTF-8 -nowarn -cp json.jar -d classes \
      $SRC/com/marvis/signin/model/Platform.java \
      $SRC/com/marvis/signin/util/Vars.java \
      $SRC/com/marvis/signin/util/Http.java \
      $SRC/com/marvis/signin/net/Dumate.java \
      验证/TestDumate.java
  java -cp .:json.jar:classes TestDumate

注：沙箱出网经过代理，TestDumate 会像 TestLive 一样放开 TLS 校验；
真机直连百度官方证书链不受影响。

TeleAgent 协议层验证脚本
==========================
验证令牌归一化、jump_url 归一化、可领取状态判定、鉴权请求头、每日任务响应解析、
平台判定与编排层未配置路径；并用假令牌打一次真实接口的鉴权拒绝路径（不接触真实账号）。
沙箱/离线环境连不上接口时自动跳过（SKIP），不算失败。

编译与运行：
  javac -encoding UTF-8 -nowarn -cp json.jar -d classes \
      $SRC/com/marvis/signin/model/Platform.java \
      $SRC/com/marvis/signin/util/Http.java \
      $SRC/com/marvis/signin/net/TeleAgent.java \
      $SRC/com/marvis/signin/net/TeleAgentSign.java \
      验证/TestTeleagent.java
  java -cp .:json.jar:classes TestTeleagent

注：TestTeleagent 与 TestDumate 一样在沙箱里放开 TLS 校验；
真机直连电信官方证书链不受影响。

