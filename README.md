# mtts

一个 Android 系统级 TTS 引擎（`com.mtts.app`）。把系统默认 TTS 引擎切到 mtts 后，任何 App 的朗读请求都会路由到这里，由离线引擎在本地合成语音。

## 功能

- 系统级 TTS：注册为 `android.speech.tts` 引擎，供系统/第三方 App 调用
- 多离线引擎：百度、搜狗、微软嵌入式、sherpa-onnx（VITS/Matcha/Kokoro）
- 本地 HTTP 网关（`/api/v1/*`），支持 `/synthesize`、`/voices`、`/engines`、`/logs` 等接口
- 语音包导入：从外部导入离线语音包（config.yaml 描述引擎与音色）
- sherpa 模型在线安装：Piper / Matcha / Kokoro / MeloTTS 按需下载
- 文本预处理：替换规则、多音字、表情符号、智能净化（jieba 分词）
- 对话分段朗读：长文本按句切分逐段合成，支持中途语速/停止中断
- 保活：前台通知常驻，本地网关与离线引擎持续在线

## 引擎

| 引擎 | 类型 | 说明 |
|---|---|---|
| `bdetts` | 离线 | 百度嵌入式合成，需导入对应语音包（.dat） |
| `sougou` | 离线 | 搜狗离线合成，需导入语音包 |
| `microsoft` | 离线 | 微软嵌入式语音 SDK |
| `sherpa` | 离线 | sherpa-onnx 多模型（VITS/Matcha/Kokoro），模型在线安装 |

## 目录结构

```
android/
  app/src/main/java/com/mtts/app/   # 主工程源码
  app/src/main/java/com/baidu/      # 百度 TTS JNI 桥接（第三方）
  app/src/main/java/com/sogou/      # 搜狗语音桥接（第三方）
  app/src/main/java/com/k2fsa/      # sherpa-onnx JNI 封装（Apache-2.0）
  app/libs/                         # 本地 jar
```

## 第三方代码与许可

本仓库包含第三方桥接/封装代码，来源与许可以各文件头部注释及上游项目为准：

- **sherpa-onnx**（`com/k2fsa/sherpa/onnx/*`）— Apache-2.0，k2-fsa 官方 JNI 封装
- **百度 / 搜狗 TTS JNI 桥接**（`com/baidu/`、`com/sogou/`）— 第三方语音 SDK 绑定代码
- 依赖：Microsoft `client-sdk-embedded`、OkHttp、Gson、SnakeYAML、Compose（均按各自许可）

内置 sherpa 模型（Piper 等）不随仓库分发，通过 App 内「模型下载」页在线安装。

## 致谢

感谢 MultiTTS 提供的思路参考；
感谢 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0）与微软嵌入式语音 SDK 提供的离线合成能力。