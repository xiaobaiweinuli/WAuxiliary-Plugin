# WAuxiliary HTTP API 插件

## 🚀 插件概述

一个基于 WAuxiliary 框架的 HTTP API 插件，提供 `RESTful` 接口来访问微信功能。

## 📋 核心功能

### 🔗 联系人管理

- `/api/contacts/friends` - 获取好友列表（包含头像、昵称、备注）
- `/api/contacts/groups` - 获取群组列表（包含群成员数量）
- `/api/contacts/all` - 获取所有联系人（好友 + 群组）
- `/api/user/info` - 获取当前登录用户信息
- `/api/contacts/avatar` - 获取指定用户的头像 URL

### 💬 消息发送

- `/api/message/sendText` - 发送文本消息
- `/api/message/sendImage` - 发送图片消息
- `/api/message/sendVideo` - 发送视频消息
- `/api/message/sendFile` - 发送文件

### ⚙️ 系统管理

- `/api/server/status` - 获取服务器状态
- `/api/server/stop` - 停止 HTTP 服务器
- `/api/server/restart` - 重启 HTTP 服务器
- `/api/server/logs` - 查看服务器日志

## 🛠️ 配置说明
```
    // 默认配置
    serverPort = getInt("http_port", 8888);      // 服务器端口，默认8888
    boolean autoStart = getBoolean("auto_start", true);  // 是否自动启动服务器
```
## ⚠️ 注意事项

1. **安全性** - API 未加密，请勿在公网暴露
2. **性能** - 大量好友/群组请求可能较慢
3. **稳定性** - 长时间运行建议定期重启
4. **兼容性** - 基于 WAuxiliary 官方 API，请保持框架更新

## 🐛 常见问题

**Q: 端口被占用怎么办？**

A: 修改配置中的 `http_port` 或重启微信

**Q: 如何停止服务器？**

A: 访问 `/api/server/stop` 接口或卸载插件

---

**提示**：插件启动后，访问 `http://localhost:8888/` 获取完整的 API 文档和 curl 示例。