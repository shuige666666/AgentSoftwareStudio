启动该程序前先启动 Docker （我开发环境采用的是 Windows ，因此需要先启动 Docker Desktop）

启动时附带 VM 参数，示例如下

```
SPRING_AI_DEEPSEEK_API_KEY=XXX;
GOOGLE_AI_STUDIO_API_KEY=XXX;
ALIBABA_API_KEY=XXX
```

取决于配置文件  `application.yml`  里 api 的名称叫什么，我现在用的是 ${ALIBABA_API_KEY} ，因此留一个 ALIBABA_API_KEY=XXX 就可以了。

配置文件：

```yaml
spring:
  ai:
    openai:
      # API Key
      api-key: ${ALIBABA_API_KEY}

server:
  port: 8080
```



前端直接访问：http://localhost:8080/stream_chat.html 即可，为其适配了一个流式接口。

## 项目文档

当前代码说明、重构方案和历史记录的入口见[文档导航](./文档/README.md)。
