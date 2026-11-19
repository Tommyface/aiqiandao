---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: df8dd3956ae26494b640a4aee7d3401d_989a6679dea211f1997d52540024e231
    ReservedCode1: tLP9dWMUjU/r1e6flaUkQgipvEseoI7uKX2MQq2LlRW9eeybOE3ZXigcmPp0IezhAPzN2RbtA7feKvVmXlKs9KDNFx+Uwc0jUGyGIdNYwYtGZX7A+iMqdHHoawweOHw6hje/gWR5HK8XhCm3W3cTM831euRqet6dGfGafv1iTbWVsyQSi+3yUznGSuI=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: df8dd3956ae26494b640a4aee7d3401d_989a6679dea211f1997d52540024e231
    ReservedCode2: tLP9dWMUjU/r1e6flaUkQgipvEseoI7uKX2MQq2LlRW9eeybOE3ZXigcmPp0IezhAPzN2RbtA7feKvVmXlKs9KDNFx+Uwc0jUGyGIdNYwYtGZX7A+iMqdHHoawweOHw6hje/gWR5HK8XhCm3W3cTM831euRqet6dGfGafv1iTbWVsyQSi+3yUznGSuI=
---


---

## v1.7.3 更新（2026-11-19）

### 网页签到引擎增强：按钮搜索与页面诊断回传

v1.7.2 真机实测：页面已能正常加载（线程修复生效），但部分活动页上未命中签到按钮。本版针对性增强：

- **iframe 内搜索**：不少活动页把内容渲染在 frame 里，主文档 `querySelectorAll` 扫不到；现在搜索范围扩展到全部同源 iframe。
- **按钮文案扩充**：新增「立即参与 / 去领取 / 领积分 / 签到领 / 参加活动 / 马上签到 / 领取今日」等变体，确认轮次补「好的 / 我知道了 / 继续」。
- **真实指针事件序列**：命中后按 `mousedown → mouseup → click` 完整序列触发，兼容依赖 `onPointerDown` 的 React 组件按钮。
- **页面诊断回传（关键）**：未命中按钮时，报错文案会带上**页面标题与可见内容前 40 字**，并自动识别三种状态——完成态（已签到/明日再来）、疑似要求登录（登录态 Cookie 未生效）、其它；用户反馈报错文案即可精准定位页面实际形态。
- **慢渲染兼容**：首轮等待从 800ms 提到 1000ms，轮次从 4 增到 5（每轮间隔 2.5s），覆盖客户端渲染较慢的 SPA。

### 版本

- versionCode 13 / versionName 1.7.3，签名证书不变，可覆盖安装升级。

---

## v1.7.2 更新（2026-11-19）

### 修复网页签到引擎在真机上崩溃（IllegalStateException）

v1.7.1 真机实测发现：网页签到引擎把 WebView 创建在了带 Looper 的子线程上，被 Android 强制拒绝——`Calling View methods on another thread than the UI thread`（View/WebView/WebSettings/CookieManager 全部只允许主线程调用，任何带 Looper 的子线程都不行）。

v1.7.2 重写了 `WebSessionClaimer` 的线程纪律：

- **WebView 的创建、加载、脚本执行、Cookie 注入、销毁全部统一 post 到主线程 Handler 执行**；
- 调用方（SignEngine 的后台工作线程）在闭锁上等待结果：阻塞的是工作线程，主线程保持空闲去处理 WebView 事件，互不卡死；
- 主线程直接调用会被立即拒绝并返回明确说明（避免自我死锁）；
- 清理动作同样回主线程（stopLoading → removeAllViews → destroy），清理后不再有后续点击。

### 版本

- versionCode 12 / versionName 1.7.2，签名证书不变，可覆盖安装升级。

---

## v1.7.1 更新（2026-11-19）

### 网页型活动彻底自动化（应用内浏览器会话）

新增 **WebSessionClaimer**（应用内浏览器会话引擎），解决 v1.7.0 的最大痛点：`action_type=web` 的活动（含签到主活动变成网页型的情况）不再只能提示手动签到，改为 App 内自动完成：

- **工作方式**：在专用线程创建无界面 WebView（不依赖主线程，不卡界面），把 `operationToken` / `user_token` 注入 `agent.teleai.com.cn` 登录态 Cookie（与 HTTP 流程同一令牌），加载活动页后由页面脚本自动定位并点击「立即签到 / 签到 / 打卡 / 领取」类按钮（含「确认 / 知道了」确认弹窗的二次点击，最多 4 轮）。
- **成败以服务端为准**：页面动作只是尝试，签到是否成功由重新查询每日任务接口复核——主活动变为 `completed` 才算成功，消息里标注「应用内浏览器自动完成」。
- **兜底策略**：页面加载失败 / 未命中按钮 / 要求真人验证时，回退为「请打开桌面端 TeleAgent 手动签到」的明确提示；任何异常都被引擎吞掉，不影响其它平台签到。
- **顺带修复**：v1.7.0 中主活动是网页型时会提前返回，导致其余可领的 API 福利活动也被跳过；v1.7.1 改为网页签到尝试后继续领取全部 API 活动，网页型福利活动（最多 2 个）也逐一自动尝试。
- **UA 策略**：页面使用桌面端 UA（福利社是桌面端专属功能），与活动页实际形态对齐。

### 版本

- versionCode 11 / versionName 1.7.1，签名证书不变，可覆盖安装升级。

### 已知限制

- 页面按钮按常见文案匹配，官方页面结构大改时可能点不中（会回退手动提示）；若活动页要求滑块/验证码等真人验证，自动流程无法通过。

---

## v1.7.0 更新（2026-11-19）

### 星辰超级智能体 TeleAgent（中国电信）签到接入

主界面新增内置平台 **TeleAgent**（kind=`teleagent`），走专用签到流程，一次签到同时完成**每日签到**和**积分福利社活动领取**：

- **接口与桌面客户端同构**：`GET /user/portal/companyStore/v3/checkDayTask?theme=light` 查每日任务（`activity_data[]`，主活动即每日签到），领取即 `GET jump_url 去掉 /superCowork/sapi 前缀`。鉴权沿用客户端同一组请求头：`X-Token` + `X-Timestamp` + `X-Nonce` + `X-App-Version` + `X-OS-Type` + `X-SuperAgent-Device-Id`，并同步携带 `Cookie: operationToken=...; user_token=...`（网页端登录态键 + 桌面客户端分区键，同值双发）。
- **令牌获取（operationToken）**：每日签到与福利社活动是桌面端专属功能（手机端暂不支持），在配置页按指引从福利社网页复制 `operationToken`（浏览器 F12 → Application → Cookies → agent.teleai.com.cn；值形如 `Em9xR4:eyJ...`，前缀是令牌自带格式，原样粘贴）。注意**不是** `operToken`（少个 ation，那是 teleai.com.cn 主域通行证），也不是 `cna`、`UM_distinctid` 等统计 Cookie；`x-token` 同值也可。兼容裸令牌 / `operationToken=xxx` / `x-token=xxx` / 整行 Cookie 多种粘贴写法；设备号留空自动生成并固定使用。令牌只存本机，不上传。
- **福利活动批量领取**：`action_status ∈ {incomplete, not_joined, fixed}` 视为可领（与客户端一致），单条失败不中断、最后汇总；`action_type=web` 的网页活动需要浏览器会话码，纯 HTTP 领不了，只提示「N 项网页活动需在客户端打开领取」；`completed` 按已签到处理，不发多余请求。
- **到期提醒**：令牌是 JWT（真实接口返回的 4001 错误文案证实），App 自动解析 `exp`，配置页与主界面提示「令牌 X 天后过期」。
- **升级迁移**：老版本升级自动补一条 TeleAgent 平台（只补一次），默认停用；导入令牌后自动启用，未导入时卡片显示「未导入」，不会把定时任务刷成失败。

### 验证情况

- 新增 `验证/TestTeleagent.java`（45 项）：令牌归一化 13 项（含 operationToken/x-token 键、误粘贴 operToken 负路径、`Em9xR4:` 前缀保留）、jump_url 归一化 4 项、可领取状态 6 项、鉴权请求头 8 项（含 Cookie 双键、前缀令牌原样进 X-Token）、响应解析 12 项、平台判定与未配置路径 6 项（含 JWT 到期解析忽略前缀）；另含真实接口负路径——用假令牌请求每日任务，返回 `HTTP 401 · code 4001「UserInfo JWT 解析失败」`，证明接口地址与鉴权头格式正确（真实令牌时 `code=0` 正常签到）。沙箱/离线环境连不上接口时自动 SKIP。
- `TestDumate` 回归全部通过，原有平台不受影响。

### 版本

- versionCode 10 / versionName 1.7.0，签名证书不变，可覆盖安装 v1.6.1。

### 已知限制

- 网页类福利活动（`action_type=web`）无法自动领取，需在桌面端打开；签到主活动若也变成网页类型，会明确提示并停在该平台等待人工处理。
- 接口结构以官方为准；变动时签到结果会提示「响应里没有 activity_data」，可用「抓包助手」对照响应排查。

---

## v1.6.1 更新（2026-11-15）

### 修复「提示缺少 bce-user-info」

v1.6.0 在保存 Cookie 和签到前会强制检查 `bce-user-info`，缺这个字段就直接拦住。实际上网页版前端在缺该 Cookie 时也会发送**空 csrfToken**（控制台只打一条 `CSRF token not found` 警告），服务端未必要求它，因此改为不阻断：

- 保存 Cookie 只要求能识别到 Cookie 且含 `BDUSS`；缺 `bce-user-info` 时给提示，但不再拒绝保存。
- 签到照常先查状态再领取，`csrfToken` 为空也照发；若服务端返回 403，再提示把 `csrfToken` 请求头一起粘贴。
- 支持粘贴抓包复制的多行文本（`Cookie:` 与 `csrfToken:` 两行），自动识别并保存 csrfToken；也支持被换行拆断的 Cookie。
- 配置页状态由「无法生成 csrfToken」改为「将用空 csrfToken 尝试签到，若返回 403 请补充 csrfToken」。

### 版本

- versionCode 9 / versionName 1.6.1，签名证书不变，可覆盖安装 v1.6.0。
- 协议测试扩到 26 项：新增「缺 bce-user-info 仍能识别 Cookie」「从请求头 csrfToken 兜底」「换行拆断的 Cookie」三条路径。

## v1.6.0 更新（2026-11-15）

### 百度搭子（DuMate）桌面端签到接入

主界面内置的「百度搭子」升级为专用流程，按桌面端身份签到，单次可领 **500 积分**（网页端已签 200 时补 300）：

- **桌面端请求头**：签到请求携带 `X-Dumate-Client-Type: desktop`。网页版 / 桌面客户端共用这一客户端标识，服务端据此按桌面端额度发放（网页端是 `web`，移动端是 `mobile`）。
- **Cookie + csrfToken**：粘贴网页版完整 Cookie（需含 `BDUSS` 与 `bce-user-info`），App 自动从 `bce-user-info` 解出 csrfToken，不再依赖 Cookie 罐里的前置响应。
- **两步签到**：先 `GET https://dumate.baidu.com/api/dumate/points/loginBonusInfo` 查桌面端状态，再 `POST https://dumate.baidu.com/api/dumate/points/loginBonus` 领取；已签也会发一次幂等领取，用于拿网页端签过后的剩余 300 分。
- **结果明细**：签到前后各取一次 `totalPoints`，成功提示「桌面端签到成功 +500 积分」，已领完提示「桌面端今日已签到」。
- **配置入口**：平台配置页新增「百度搭子 Cookie（桌面端签到）」区块，粘贴 Cookie → 保存并启用；Cookie 只存本机，不上传。
- **升级迁移**：v1.5.0 的通用「百度搭子」模板原地升级为 `kind=dumate`，保留已有 Cookie / 名称 / 开关，只补桌面端标识与标准地址；没填 Cookie 的旧模板会自动停用，避免每天刷失败。
- **协议细节**：接口、`X-Dumate-Client-Type: desktop` 的依据与验证边界见 [`百度搭子桌面端说明.md`](百度搭子桌面端说明.md)。

### 验证情况

- 协议层 22 项离线检查 + 真实接口假 Cookie 鉴权失败路径（HTTP 401 / code 10001）。
- Java 全量编译、d8、签名通过；APK 为 v1.6.0（versionCode 8），证书与 v1.5.0 相同，可直接覆盖安装。
- 本沙箱没有真实百度账号，**500 / 300 积分的实际到账未用真实账号复核**；桌面端标识依据来自网页版前端 `buildDumateHeaders` 的映射（desktop→desktop、web→web、mobile→mobile），接口变动时可用抓包助手对照。

### 版本与升级

- versionCode 8 / versionName 1.6.0，包名 `com.marvis.signin` 与签名证书均未变，**可覆盖安装 v1.5.0，配置不丢**。

### 已知限制

- 真实签到金额需你用自己的账号确认；Cookie 失效（401 / 10001）时重新登录网页版更新即可。
- 桌面端额度以官方活动规则为准，接口或风控调整时以抓包结果为准。

## v1.5.0 更新（2026-11-15）

### Trae CN 自动签到接入

主界面新增内置平台 **Trae CN**（字节 Trae IDE），走专用签到流程，和 MiniMax 一样预置在列表里、默认停用：

- **本机解密登录态**：Trae 客户端把凭据加密存在 `storage.json` 的 `iCubeAuthInfo://icube.cloudide` 字段里。平台配置页新增「Trae CN 登录态导入」区块，把该文件全文粘贴进去点「解析并保存登录态」，App 用仓库同款算法在本机完成 AES-128-CBC 解密 + SHA-512 完整性校验，提取 token、过期时间和设备号，**全程不联网、不上传**。
- **设备号绑定**：签到接口必须携带 `x-device-id`（取自 `storage.json` 里 `iCubeAuthInfo://icube-dc:` 键的后缀），否则上游返回 9004。App 会自动解析并绑定，也可以在输入框手动修正。
- **两步签到**：先 `POST /trae/api/v2/ug/checkin_credits/status` 看今天能不能领，可领才发 `POST /trae/api/v2/ug/checkin_credits/claim`；已签到（`checked_in=true`）时一个写请求都不发。
- **明确的结果提示**：签到成功显示「+N 积分」；`9074` 提示「Trae 拒绝第三方签到，请打开官方客户端」；`9004` 提示重新导入绑定设备号；`1001` / HTTP 401 / 403 提示登录态失效、重新导入 `storage.json`。
- **登录态过期提醒**：优先用 `storage.json` 里的 `expiredAt`（其次 JWT 的 `exp`）算到期时间，配置页与主界面都会提示「令牌 X 天后过期」；Trae 的访问令牌由官方客户端续期，过期后重新导入一次即可。

### 其它细节

- 导入成功会自动启用该平台并参与每日定时签到；未导入时卡片显示「未导入」，不会把定时任务刷成失败。
- 升级后自动补一条 Trae CN 平台（只补一次，之后你手动删掉也不会再自动加回）。
- 从 `storage.json` 解析出的设备号随平台配置一起持久化（`credDeviceId`），旧版本配置读取时默认为空，不影响升级。

### 版本与升级

- versionCode 7 / versionName 1.5.0，包名 `com.marvis.signin` 与签名证书均未变，**可覆盖安装 v1.4.0，配置不丢**。

### 已知限制

- 本沙箱没有 Android 运行环境，本轮完成编译与静态校验；真机上的 `storage.json` 粘贴/解密与定时签到需你实机确认。
- Trae CN 的接口、活动规则以官方为准；接口变动时可用「抓包助手」对照报错调整。

## v1.1.0 更新（2026-11-12）

### Cookie 自动续期
- 每次签到响应里的 `Set-Cookie` 会被解析并合并进该平台独立的 Cookie 罐（本地键 `jar_<平台id>`，存储文件 `signin_cookie_v1`）。
- 下次请求自动拼成 `Cookie` 请求头发出去；若你在平台配置的请求头里已经手写了 `Cookie`，以手写内容为准，不会被覆盖。
- 同名 Cookie 以服务端最新值为准；`Max-Age<=0` 或 `Expires` 落在 1970 年的条目按失效处理并移除。
- 抓包列表的条目右侧会显示 `Cookie N`，表示本次响应新写入或更新了 N 个 Cookie。

### 抓包助手
- 主界面新增「抓包助手」入口。
- 记录最近 60 次签到请求的完整报文：请求方法、URL、全部请求头、请求体，以及响应状态行、全部响应头、响应体（响应体最多读取 8192 字节）。
- 点条目看全文（等宽字体、可选中复制），长按条目或点「复制全文」可整段复制，方便对照接口文档改配置。
- 记录只存在本机 SharedPreferences（`signin_trace_v1`），不上传任何数据；「清空」只删抓包日志，不动平台配置与签到记录。

### 预置平台模板（四个）
首次启动自动写入四个平台的真实接口模板，URL、方法、请求头、请求体、成功关键词、前置请求 URL 全部预填，**只有凭据留空待你填写**：

| 平台 | 签到接口 | 需要你填的凭据 |
| --- | --- | --- |
| WorkBuddy | `POST https://www.workbuddy.cn/v2/billing/meter/daily-checkin` | Authorization 的 Bearer token、X-User-Id |
| 百度搭子（v1.6.0 起为桌面端专用流程） | `POST https://dumate.baidu.com/api/dumate/points/loginBonus` | 请求头里的 Cookie（需含 BDUSS、bce-user-info） |
| Qoder | `POST https://openapi.qoder.com.cn/sash/api/v1/me/campaigns/{{pre:...}}/claim` | Authorization 的 Bearer token |
| AIPY | `POST https://www.trustoken.cn/api/user/checkin` | Authorization 的 Bearer token、New-Api-User |

老版本升级时只给「同名且 URL 仍为空」的平台补模板，绝不覆盖你已经填好的内容；不需要的平台直接删掉即可。

### 前置请求与模板变量
- 平台配置新增「前置请求 URL（可空）」：填了就先发一次 GET，拿响应里的 `Set-Cookie` 写进该平台 Cookie 罐，响应体则留给模板变量取值。
- 主请求的 URL、请求头、请求体都支持三种占位符，发送前实时替换：
  - `{{cookie:名字}}` — 取 Cookie 罐里同名 Cookie 的值（例：百度搭子的 `csrftoken: {{cookie:bce-user-info}}`）
  - `{{pre:$.a.b[0].c}}` — 从前置响应 JSON 里按路径取值
  - `{{pre:raw}}` — 整段前置响应原文
- 路径支持数组下标 `[0]`，也支持条件过滤 `[actionType=CLAIM_BENEFIT&claimStatus=CLAIMABLE]`（多个条件用 `&` 连接，命中第一条即取）。Qoder 的 campaignId 就靠这个从前置活动列表里自动取，不用手填。
- 前置响应体摘要会附在抓包记录的请求报文顶部，方便排查「取不到值」的问题。

### 版本与兼容
- versionCode 2 / versionName 1.1.0，包名 `com.marvis.signin` 与签名证书均未变，可直接覆盖安装旧版。

### 已知限制
- 本沙箱没有 Android 运行环境，本轮只完成编译、签名与静态校验；真机行为（各家 Set-Cookie 写法差异、不同厂商后台保活策略）需要你实机确认。
- 只回收 `Set-Cookie`，不处理 `Set-Cookie2`；Cookie 未按 Domain/Path 做作用域细分，但每个平台一个独立罐，平台之间不会串。
## v1.2.0 更新（2026-11-14）

### 凭据自动续期（核心）
一次抓包，凭据长期有效：签到流程里自动把续期接口返回的新 token / Cookie 写回平台配置，下次直接用它，不用再手动重抓。

平台配置页新增「凭据与自动续期（选填）」区块，共 5 个输入项：

| 配置项 | 说明 |
| --- | --- |
| 当前凭据 | 你的 token 或整条 Cookie。签到请求里用 `{{token}}` 引用，例如 `Authorization: Bearer {{token}}` |
| 续期接口 URL | 拿新凭据的接口；留空则该平台不启用自动续期 |
| 续期请求方式 | POST（默认）/ GET |
| 续期请求体 | 可留空；可用 `{{token}}` 引用旧凭据（如 refresh_token 换 access_token 的接口） |
| 新凭据提取路径 | JSON 路径如 `$.data.accessToken`；或填 `@set-cookie` 表示把响应 `Set-Cookie` 当作新凭据 |
| 主动续期周期（小时） | 填 24 表示每 24 小时主动续一次；填 0 或留空则只在签到失败时被动续期 |

### 三种触发方式
1. **到点主动续期**：距上次续期超过设定小时数，签到前先续一次再签。
2. **失败被动续期**：签到返回 401 / 403，或响应体出现 `expired`、`unauthorized`、`invalid token`、`token失效`、`未登录`、`登录已过期` 等字样，自动续期一次并**重试一次**签到（只重试一次，避免刷接口）。
3. **手动测试**：配置页「只测续期接口」按钮，单独验证续期链路通不通，成功会直接把新凭据填回「当前凭据」输入框。

### 续期结果
- 续期成功后新凭据写入平台配置并立即持久化，`lastRenewAt` 同步更新，供下次周期判断。
- 抓包记录里会多一条 `[续期] POST <url>` 报文，并标注「凭据已更新」或「续期失败：<原因>」；凭据在日志里只露头 6 位和尾 4 位，不落明文。
- 内置模板（WorkBuddy / Qoder / AIPY）的请求头已改为 `Authorization: Bearer {{token}}`，你只需把 token 粘进「当前凭据」。
- 续期接口地址没有公开文档，**不预置**，由你按各家实际接口填写；填了 `@set-cookie` 的平台可只靠 Cookie 续期，不用管 JSON 路径。

### 签名与升级（重要）
- 旧版 build.sh 每次构建都重新生成签名证书，导致每一版证书都不同、装新包必须先卸载。本版已把证书固定到工程内 `keystore/signin.keystore`（不随 build 目录清理），**从此以后各版本可覆盖安装、配置不丢**。
- 也因此：从 v1.1.0 升到 v1.2.0 这一版，因为旧包证书确实不同，**需要先卸载旧版再装**；装完这一版之后，后续升级都能直接覆盖。
- versionCode 3 / versionName 1.2.0，包名 `com.marvis.signin` 未变。

### 已知限制
- 续期只在签到流程内触发，不做后台常驻定时续期；主动周期依赖每日定时签到任务被唤醒。
- `@set-cookie` 模式取的是该平台 Cookie 罐的整条 Cookie 串，不做单字段拆分。
- 本沙箱无 Android 运行环境，本轮只完成编译、签名与静态校验（dex 内已确认含 `et_cred`、`et_renew_url`、`@set-cookie`），真机续期行为需你实机验证。

*（内容由AI生成，仅供参考）*

## v1.4.0 更新（2026-11-15）

### MiniMax Code（海螺）自动签到接入

主界面新增内置平台 **MiniMax Code**，走的是 MiniMax 官方 OAuth 2.0 设备码流程（RFC 8628），令牌由 App 自己管理，不用再从别处复制 token：

- **一键授权**：平台配置页点「授权 MiniMax」→ App 向 `account.minimax.cn/oauth2/device/code` 申请设备码（带 PKCE S256）→ 自动打开 MiniMax 官方登录页 → 登录确认后 App 轮询 `account.minimax.cn/oauth2/token` 换到 access token 与 refresh token，令牌只存在本机。
- **令牌自动轮换**：access token 约 1 小时就过期，refresh token 长期有效但**一次性**（刷新后上游会换新的）。App 会在过期前自动续期，并把新的 refresh token 一起写回本地，不会出现「续一次就再也续不上」的情况。
- **两步签到**：先 `GET /minimax-cloud/api/v1/signin/status` 看今天能不能领（状态为 2 才可领），可领才发 `POST /minimax-cloud/api/v1/signin/claim`；今天已经领过就一个写请求都不发。上游返回 `claim_result=1` 视为领取成功、`claim_result=2` 视为「已签到」。
- **自愈**：401 时刷新一次令牌并重试一次，不死循环。

### 点击签到

- 每个平台卡片新增 **「签到」按钮**，点一下立刻给该平台签到（以前只能长按卡片）。
- MiniMax 未授权时卡片状态显示「未授权」，点签到会提示先完成一次授权。
- MiniMax 的签到结果会带上明细，例如「签到成功 +400 算力」「今天已签到」。

### 其它细节

- MiniMax 平台预置时**默认停用**：授权成功的那一刻会自动启用并开始参与每日定时签到，所以没授权时不会每天把定时任务刷成失败。
- 升级后自动补一条 MiniMax Code 平台（只补一次，之后你手动删掉也不会再自动加回）。

### 其他修复

- `Http` 发 POST 时必须真的取一次输出流：以前 body 为空的 POST 会被 `HttpURLConnection` 悄悄降级成 GET（Qoder / AIPY 这类空 body 的模板会受影响）。本次一并修掉。
- 用户自己写的 `Content-Type` 不再被默认值覆盖。
- 授权等待期间用前台服务保活，避免切到浏览器后进程被回收导致轮询中断；回到应用还能接着轮询（设备码状态存在本地）。

### 稳定性加固（这一版重点排查过的地方）

- **令牌不会被旧快照覆盖**：MiniMax 的 refresh token 是一次性的（上游每次刷新都会换发）。编辑页保存配置时现在一律从存储取最新令牌，绝不会把已经失效的旧 RT 写回去——否则「授权一次长期可用」会直接失效。
- **不会「假成功」**：解析不出今天的状态格子时判为**失败**（而不是当成「今天已签到」）。否则 App 会天天显示「已签到」却一天额度都没领到。`is_today` 兼容 true / 1 / "1"，并加了按日期匹配的兜底。
- **并发保护**：同一个平台同一时刻只允许一个签到流程。点两次「签到」不会并发刷新同一个一次性 refresh token（否则会 invalid_grant，甚至被上游吊销整个令牌族）。停用的平台不会被「签到」按钮点到。
- **授权不误伤守护**：授权期间借用的前台服务，只有「本来没开守护」时才由 App 拉起，也只有 App 自己拉起的才由 App 停——不会把你已经开着的「签到守护」关掉。
- **页面重建不重复轮询**：授权轮询是跨页面存活的，切页面/旋转不会起第二条轮询线程，也不会在页面关闭后继续弹窗。
- **批量签到不会永久卡死**：单个平台执行加了异常兜底，批量标志也放进了 finally。

### 版本与升级

- versionCode 6 / versionName 1.4.0，包名 `com.marvis.signin` 与签名证书均未变，**可覆盖安装 v1.3.1，配置不丢**。

### 已知限制

- 本沙箱没有 Android 运行环境，本轮完成编译、签名与静态校验；真机上的授权跳转与后台保活行为需要你实机确认。
- MiniMax 的每日额度、活动规则与接口地址以官方为准，接口变动时请用「抓包助手」对照报错调整。

## v1.3.1 更新（2026-11-14）

### 修复 Qoder 签到
- 活动 ID 原来从不存在的 `.id` 字段取值，导致签到 URL 拼成 `campaigns//claim` 必然失败；现改为取接口真实返回的 `.campaignId`。
- 取值不再要求 `claimStatus=CLAIMABLE`，当天已领取时也能正常发出签到请求并得到明确结果。

### 「已签到」状态
- 平台配置新增「已签到关键词」（可空，多个用 `|` 分隔）：响应命中任一词即按成功处理，结果提示「已签到」，漏签补做不会重复执行。
- Qoder 预置 `"replayed":true`（接口对重复领取的返回标记，已实测）；WorkBuddy / 百度搭子 / AIPY 分别预置「今天已签到」「已签到|已领取」「已签到」。

### 签名与升级（重要）
- 此前源码包未包含 keystore，本版证书为重新生成，与旧 v1.3.0 不同：**需先卸载旧版再安装**（本机配置会清空，token 需重新填写）。
- 证书已固定在工程内 `keystore/signin.keystore`（随源码包附带），此后用本源码构建的版本均可覆盖安装。
- versionCode 5 / versionName 1.3.1，包名 `com.marvis.signin` 未变。

### 已知限制
- 本沙箱无 Android 运行环境，本轮完成编译、签名与静态校验，并用真实接口响应验证了「已领取→已签到、可领取→签到成功」的判定；真机行为需实机确认。

> AI生成
