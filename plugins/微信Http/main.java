import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * WAuxiliary HTTP API 插件 - 优化版
 * 修复: 视频发送、线程管理、资源泄漏
 */

String pluginId = "com.plugin.httpapi";
String pluginName = "微信-HTTP API 插件";
String pluginAuthor = "星霜 | 优化版";
String pluginVersion = "1.1.0";

ServerSocket serverSocket = null;
volatile boolean serverRunning = false;
int serverPort = 8888;

// 线程池管理
ExecutorService threadPool = null;
Thread serverThread = null;

// 消息队列 - 用于异步发送
LinkedBlockingQueue<Runnable> messageQueue = new LinkedBlockingQueue<>(100);
Thread messageWorker = null;

/** 插件加载 */
void onLoad() {
    log("===== HTTP API 插件开始加载 =====");
    toast("HTTP API 插件正在加载...");

    try {
        // 加载配置
        serverPort = getInt("http_port", 8888);
        boolean autoStart = getBoolean("auto_start", true);
        log("配置: 端口=" + serverPort + ", 自动启动=" + autoStart);

        // 初始化线程池
        initThreadPool();

        // 自动启动服务器
        if (autoStart) {
            startServer();
        }

        toast("HTTP API 插件加载完成");
        log("插件加载完成");
    } catch (Exception e) {
        log("插件加载失败: " + e);
        toast("HTTP API 插件加载失败");
    }
}

/** 插件卸载 */
void onUnLoad() {
    log("===== HTTP API 插件开始卸载 =====");

    try {
        // 停止服务器
        if (serverRunning) {
            stopServer();
        }

        // 关闭消息处理线程
        stopMessageWorker();

        // 关闭线程池
        shutdownThreadPool();

        // 等待资源释放
        Thread.sleep(1000);

        log("插件卸载完成");
        toast("HTTP API 插件已卸载");
    } catch (Exception e) {
        log("插件卸载失败: " + e);
    }
}

/** 监听收到消息 */
void onHandleMsg(Object msgInfoBean) {
    // 可选：记录收到的消息
    // writeLog("收到消息: " + msgInfoBean);
}

/** 单击发送按钮 */
boolean onClickSendBtn(String text) {
    return false; // 不拦截
}

/** 监听成员变动 */
void onMemberChange(String type, String groupWxid, String userWxid, String userName) {
    writeLog("群成员变动: " + type + ", 群:" + groupWxid + ", 用户:" + userWxid);
}

/** 监听好友申请 */
void onNewFriend(String wxid, String ticket, int scene) {
    writeLog("新好友申请: " + wxid);
}

// ==================== 线程池管理 ====================

void initThreadPool() {
    if (threadPool == null || threadPool.isShutdown()) {
        threadPool = Executors.newFixedThreadPool(10, new ThreadFactory() {
            int count = 0;
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "HTTP-Worker-" + (++count));
                t.setDaemon(true);
                return t;
            }
        });
        log("线程池初始化完成");
    }
}

void shutdownThreadPool() {
    if (threadPool != null && !threadPool.isShutdown()) {
        try {
            threadPool.shutdown();
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
            log("线程池已关闭");
        } catch (Exception e) {
            log("关闭线程池失败: " + e);
            threadPool.shutdownNow();
        }
    }
}

// ==================== 消息队列处理 ====================

void startMessageWorker() {
    if (messageWorker != null && messageWorker.isAlive()) {
        return;
    }

    messageWorker = new Thread(new Runnable() {
        public void run() {
            log("消息处理线程启动");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Runnable task = messageQueue.poll(1, TimeUnit.SECONDS);
                    if (task != null) {
                        task.run();
                    }
                } catch (InterruptedException e) {
                    log("消息处理线程被中断");
                    break;
                } catch (Exception e) {
                    log("消息处理异常: " + e);
                }
            }
            log("消息处理线程退出");
        }
    }, "Message-Worker");
    messageWorker.setDaemon(true);
    messageWorker.start();
}

void stopMessageWorker() {
    if (messageWorker != null && messageWorker.isAlive()) {
        messageWorker.interrupt();
        try {
            messageWorker.join(2000);
        } catch (InterruptedException e) {
            // ignore
        }
    }
    messageQueue.clear();
}

// ==================== 服务器管理 ====================

void startServer() {
    if (serverRunning) {
        toast("HTTP 服务器已在运行");
        return;
    }

    try {
        // 确保旧的 socket 已关闭
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
            Thread.sleep(1000);
        }

        // 初始化线程池和消息处理线程
        initThreadPool();
        startMessageWorker();

        serverSocket = new ServerSocket(serverPort);
        serverSocket.setReuseAddress(true);
        serverSocket.setSoTimeout(1000); // 设置超时,便于优雅关闭
        serverRunning = true;

        // 创建服务器线程
        serverThread = new Thread(new Runnable() {
            public void run() {
                runServer();
            }
        }, "HTTP-Server-Main");
        serverThread.setDaemon(true);
        serverThread.start();

        log("HTTP 服务器启动成功，端口: " + serverPort);
        toast("HTTP 服务器启动成功，端口: " + serverPort);
        notify("HTTP API", "服务器已启动，端口: " + serverPort);

    } catch (java.net.BindException e) {
        log("端口被占用: " + e.getMessage());
        toast("端口 " + serverPort + " 被占用");
        notify("HTTP API 错误", "端口被占用，请重启微信或更换端口");
        serverRunning = false;
    } catch (Exception e) {
        log("启动服务器失败: " + e);
        toast("启动服务器失败: " + e.getMessage());
        serverRunning = false;
    }
}

void stopServer() {
    if (!serverRunning) {
        return;
    }

    log("正在停止 HTTP 服务器...");
    serverRunning = false;

    try {
        // 关闭 ServerSocket
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }

        // 等待服务器线程退出
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.join(3000);
            if (serverThread.isAlive()) {
                serverThread.interrupt();
            }
        }

        log("HTTP 服务器已停止");
        toast("HTTP 服务器已停止");

    } catch (Exception e) {
        log("停止服务器异常: " + e);
    }
}

void runServer() {
    log("服务器线程开始运行");
    
    while (serverRunning) {
        try {
            Socket client = serverSocket.accept();
            
            // 使用线程池处理请求
            if (threadPool != null && !threadPool.isShutdown()) {
                threadPool.execute(new Runnable() {
                    public void run() {
                        handleRequest(client);
                    }
                });
            }
            
        } catch (java.net.SocketTimeoutException e) {
            // 超时是正常的,继续循环
            continue;
        } catch (java.net.SocketException e) {
            if (serverRunning) {
                log("Socket异常: " + e.getMessage());
            }
            break;
        } catch (Exception e) {
            if (serverRunning) {
                log("接受连接失败: " + e);
            }
        }
    }

    log("服务器线程已结束");
}

// ==================== 请求处理 ====================

void handleRequest(Socket socket) {
    BufferedReader reader = null;
    OutputStream output = null;
    
    try {
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        output = socket.getOutputStream();

        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            return;
        }

        log("请求: " + requestLine);

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            sendResponse(output, 400, "text/plain", "Bad Request");
            return;
        }

        String method = parts[0];
        String uri = parts[1];

        // 读取请求头
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim().toLowerCase(), 
                          line.substring(idx + 1).trim());
            }
        }

        // 读取请求体
        String body = "";
        if ("POST".equals(method)) {
            String lenStr = headers.get("content-length");
            if (lenStr != null) {
                int len = Integer.parseInt(lenStr);
                char[] buf = new char[len];
                int read = reader.read(buf, 0, len);
                body = new String(buf, 0, read);
            }
        }

        // 解析参数
        Map<String, String> params = new HashMap<>();
        int qIdx = uri.indexOf('?');
        String path = qIdx >= 0 ? uri.substring(0, qIdx) : uri;
        if (qIdx >= 0) {
            parseParams(uri.substring(qIdx + 1), params);
        }
        if ("POST".equals(method) && !body.isEmpty()) {
            parseParams(body, params);
        }

        // 路由处理
        String response = routeRequest(path, params);
        String contentType = path.equals("/")
                ? "text/html; charset=utf-8" 
                : "application/json; charset=utf-8";

        sendResponse(output, 200, contentType, response);

    } catch (Exception e) {
        log("处理请求失败: " + e);
        try {
            if (output != null) {
                sendResponse(output, 500, "text/plain", "Internal Server Error");
            }
        } catch (Exception ignored) {}
    } finally {
        // 关闭资源
        try {
            if (reader != null) reader.close();
            if (output != null) output.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (Exception e) {
            log("关闭连接失败: " + e);
        }
    }
}

void parseParams(String query, Map<String, String> params) {
    if (query == null || query.isEmpty()) return;
    
    for (String pair : query.split("&")) {
        int idx = pair.indexOf('=');
        if (idx > 0) {
            try {
                String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                params.put(key, value);
            } catch (Exception e) {
                log("解析参数失败: " + pair);
            }
        }
    }
}

// ==================== 路由处理 ====================

String routeRequest(String path, Map<String, String> params) {
    try {
        switch (path) {
            case "/": 
                return getWelcomePage();
            case "/api/contacts/friends": 
                return getFriendsJson();
            case "/api/contacts/groups": 
                return getGroupsJson();
            case "/api/contacts/all": 
                return getAllContactsJson();
            case "/api/user/info": 
                return handleGetUserInfo();
            case "/api/contacts/avatar": 
                return handleGetAvatar(params);
            case "/api/message/sendText": 
                return handleSendText(params);
            case "/api/message/sendImage": 
                return handleSendImage(params);
            case "/api/message/sendVideo": 
                return handleSendVideo(params);
            case "/api/message/sendVoice": 
                return handleSendVoice(params);
            case "/api/message/sendFile": 
                return handleSendFile(params);
            case "/api/server/status": 
                return handleServerStatus();
            case "/api/server/stop": 
                return handleServerStop();
            case "/api/server/restart": 
                return handleServerRestart();
            default: 
                return createError("接口不存在: " + path);
        }
    } catch (Exception e) {
        log("路由处理失败: " + path + " - " + e);
        return createError("处理失败: " + e.getMessage());
    }
}

// ==================== 联系人接口 ====================

JSONObject buildContactItem(Object contact) {
    try {
        Class<?> cls = contact.getClass();
        String wxid = (String) cls.getMethod("getWxid").invoke(contact);
        String nickname = (String) cls.getMethod("getNickname").invoke(contact);
        String remark = (String) cls.getMethod("getRemark").invoke(contact);

        JSONObject item = new JSONObject();
        item.put("wxid", wxid);
        item.put("nickname", nickname != null ? nickname : "");
        item.put("remark", remark != null ? remark : "");

        try {
            String alias = (String) cls.getMethod("getAlias").invoke(contact);
            item.put("alias", alias != null ? alias : "");
        } catch (Exception ignored) {
            item.put("alias", "");
        }

        return item;
    } catch (Exception e) {
        log("构建联系人项失败: " + e);
        return null;
    }
}

JSONObject buildGroupItem(Object group) {
    try {
        Class<?> cls = group.getClass();
        String roomId = (String) cls.getMethod("getRoomId").invoke(group);
        if (roomId == null || roomId.isEmpty()) return null;

        String name = (String) cls.getMethod("getName").invoke(group);
        String remark = (String) cls.getMethod("getRemark").invoke(group);

        JSONObject item = new JSONObject();
        item.put("wxid", roomId);
        item.put("nickname", name != null ? name : "");
        item.put("remark", remark != null ? remark : "");

        try {
            item.put("memberCount", getGroupMemberCount(roomId));
        } catch (Exception ignored) {
            item.put("memberCount", 0);
        }

        return item;
    } catch (Exception e) {
        return null;
    }
}

String getFriendsJson() {
    try {
        List friends = getFriendList();
        JSONArray arr = new JSONArray();
        
        for (Object f : friends) {
            JSONObject item = buildContactItem(f);
            if (item != null) {
                arr.put(item);
            }
        }
        
        log("获取好友列表: " + arr.length() + " 个");
        return createSuccess(arr);
    } catch (Exception e) {
        log("获取好友失败: " + e);
        return createError("获取好友失败: " + e.getMessage());
    }
}

String getGroupsJson() {
    try {
        List groups = getGroupList();
        JSONArray arr = new JSONArray();
        
        for (Object g : groups) {
            JSONObject item = buildGroupItem(g);
            if (item != null) {
                arr.put(item);
            }
        }
        
        log("获取群组列表: " + arr.length() + " 个");
        return createSuccess(arr);
    } catch (Exception e) {
        log("获取群组失败: " + e);
        return createError("获取群组失败: " + e.getMessage());
    }
}

String getAllContactsJson() {
    try {
        JSONObject result = new JSONObject();

        // 好友
        JSONArray friendsArr = new JSONArray();
        List friends = getFriendList();
        for (Object f : friends) {
            JSONObject item = buildContactItem(f);
            if (item != null) {
                friendsArr.put(item);
            }
        }
        result.put("friends", friendsArr);

        // 群组
        JSONArray groupsArr = new JSONArray();
        List groups = getGroupList();
        for (Object g : groups) {
            JSONObject item = buildGroupItem(g);
            if (item != null) {
                groupsArr.put(item);
            }
        }
        result.put("groups", groupsArr);

        log("获取所有联系人: 好友" + friendsArr.length() + "个, 群组" + groupsArr.length() + "个");
        return createSuccess(result);
    } catch (Exception e) {
        log("获取所有联系人失败: " + e);
        return createError("获取失败: " + e.getMessage());
    }
}

String handleGetUserInfo() {
    try {
        JSONObject info = new JSONObject();
        String wxid = getLoginWxid();
        String alias = getLoginAlias();
        
        info.put("wxid", wxid != null ? wxid : "");
        info.put("alias", alias != null ? alias : "");
        
        log("获取用户信息: " + wxid);
        return createSuccess(info);
    } catch (Exception e) {
        log("获取用户信息失败: " + e);
        return createError("获取用户信息失败: " + e.getMessage());
    }
}

String handleGetAvatar(Map params) {
    try {
        String username = (String) params.get("username");
        if (username == null || username.isEmpty()) {
            return createError("缺少参数: username");
        }
        
        String avatarUrl = getAvatarUrl(username);
        
        JSONObject result = new JSONObject();
        result.put("username", username);
        result.put("avatarUrl", avatarUrl != null ? avatarUrl : "");
        
        return createSuccess(result);
    } catch (Exception e) {
        log("获取头像失败: " + e);
        return createError("获取头像失败: " + e.getMessage());
    }
}

// ==================== 消息发送接口 ====================

String handleSendText(Map params) {
    try {
        String wxid = (String) params.get("wxid");
        String content = (String) params.get("content");
        
        if (wxid == null || wxid.isEmpty()) {
            return createError("缺少参数: wxid");
        }
        if (content == null || content.isEmpty()) {
            return createError("缺少参数: content");
        }
        
        // 直接发送,不使用队列
        sendText(wxid, content);
        
        log("发送文本: " + wxid + " -> " + content.substring(0, Math.min(content.length(), 50)));
        return createSuccess("发送成功");
    } catch (Exception e) {
        log("发送文本失败: " + e);
        return createError("发送失败: " + e.getMessage());
    }
}

String handleSendImage(Map params) {
    try {
        String wxid = (String) params.get("wxid");
        String path = (String) params.get("path");
        
        if (wxid == null || wxid.isEmpty()) {
            return createError("缺少参数: wxid");
        }
        if (path == null || path.isEmpty()) {
            return createError("缺少参数: path");
        }
        
        // 验证文件
        File file = new File(path);
        if (!file.exists()) {
            log("图片文件不存在: " + path);
            return createError("图片文件不存在: " + path);
        }
        if (!file.canRead()) {
            log("图片文件无法读取: " + path);
            return createError("图片文件无法读取，请检查权限");
        }
        
        long fileSize = file.length();
        log("准备发送图片: " + path + ", 大小: " + fileSize + " 字节");
        
        // 使用消息队列异步发送
        final String finalWxid = wxid;
        final String finalPath = path;
        messageQueue.offer(new Runnable() {
            public void run() {
                try {
                    log("开始发送图片: " + finalPath);
                    sendImage(finalWxid, finalPath);
                    log("图片发送成功: " + finalWxid);
                } catch (Exception e) {
                    log("图片发送失败: " + e);
                }
            }
        });
        
        return createSuccess("图片已加入发送队列");
    } catch (Exception e) {
        log("处理图片发送请求失败: " + e);
        return createError("发送失败: " + e.getMessage());
    }
}

String handleSendVideo(Map params) {
    try {
        String wxid = (String) params.get("wxid");
        String path = (String) params.get("path");
        
        if (wxid == null || wxid.isEmpty()) {
            return createError("缺少参数: wxid");
        }
        if (path == null || path.isEmpty()) {
            return createError("缺少参数: path");
        }
        
        // 验证文件
        File file = new File(path);
        if (!file.exists()) {
            log("视频文件不存在: " + path);
            return createError("视频文件不存在: " + path);
        }
        if (!file.canRead()) {
            log("视频文件无法读取: " + path);
            return createError("视频文件无法读取，请检查权限");
        }
        
        long fileSize = file.length();
        log("准备发送视频: " + path + ", 大小: " + fileSize + " 字节");
        
        // 检查文件格式
        String fileName = file.getName().toLowerCase();
        if (!fileName.endsWith(".mp4") && !fileName.endsWith(".avi") && 
            !fileName.endsWith(".mov") && !fileName.endsWith(".3gp")) {
            log("不支持的视频格式: " + fileName);
            return createError("不支持的视频格式，请使用 mp4/avi/mov/3gp");
        }
        
        // 使用消息队列异步发送
        final String finalWxid = wxid;
        final String finalPath = path;
        messageQueue.offer(new Runnable() {
            public void run() {
                try {
                    log("开始发送视频: " + finalPath);
                    sendVideo(finalWxid, finalPath);
                    log("视频发送成功: " + finalWxid);
                } catch (Exception e) {
                    log("视频发送失败: " + e);
                }
            }
        });
        
        return createSuccess("视频已加入发送队列");
    } catch (Exception e) {
        log("处理视频发送请求失败: " + e);
        return createError("发送失败: " + e.getMessage());
    }
}

String handleSendVoice(Map params) {
    try {
        String wxid = (String) params.get("wxid");
        String path = (String) params.get("path");
        String durationStr = (String) params.get("duration");
        
        if (wxid == null || wxid.isEmpty()) {
            return createError("缺少参数: wxid");
        }
        if (path == null || path.isEmpty()) {
            return createError("缺少参数: path");
        }
        
        File file = new File(path);
        if (!file.exists()) {
            return createError("语音文件不存在: " + path);
        }
        
        int duration = 0;
        if (durationStr != null && !durationStr.isEmpty()) {
            try {
                duration = Integer.parseInt(durationStr);
            } catch (NumberFormatException e) {
                return createError("duration 参数必须是数字");
            }
        }
        
        final String finalWxid = wxid;
        final String finalPath = path;
        final int finalDuration = duration;
        
        messageQueue.offer(new Runnable() {
            public void run() {
                try {
                    log("开始发送语音: " + finalPath);
                    if (finalDuration > 0) {
                        sendVoice(finalWxid, finalPath, finalDuration);
                    } else {
                        sendVoice(finalWxid, finalPath);
                    }
                    log("语音发送成功: " + finalWxid);
                } catch (Exception e) {
                    log("语音发送失败: " + e);
                }
            }
        });
        
        return createSuccess("语音已加入发送队列");
    } catch (Exception e) {
        log("处理语音发送请求失败: " + e);
        return createError("发送失败: " + e.getMessage());
    }
}

String handleSendFile(Map params) {
    try {
        String wxid = (String) params.get("wxid");
        String path = (String) params.get("path");
        
        if (wxid == null || wxid.isEmpty()) {
            return createError("缺少参数: wxid");
        }
        if (path == null || path.isEmpty()) {
            return createError("缺少参数: path");
        }
        
        File file = new File(path);
        if (!file.exists()) {
            return createError("文件不存在: " + path);
        }
        if (!file.canRead()) {
            return createError("文件无法读取，请检查权限");
        }
        
        String fileName = file.getName();
        log("准备发送文件: " + fileName + ", 路径: " + path);
        
        final String finalWxid = wxid;
        final String finalPath = path;
        final String finalFileName = fileName;
        
        messageQueue.offer(new Runnable() {
            public void run() {
                try {
                    log("开始发送文件: " + finalFileName);
                    shareFile(finalWxid, finalFileName, finalPath, "");
                    log("文件发送成功: " + finalWxid);
                } catch (Exception e) {
                    log("文件发送失败: " + e);
                }
            }
        });
        
        return createSuccess("文件已加入发送队列");
    } catch (Exception e) {
        log("处理文件发送请求失败: " + e);
        return createError("发送失败: " + e.getMessage());
    }
}

// ==================== 系统接口 ====================

String handleServerStatus() {
    try {
        JSONObject status = new JSONObject();
        status.put("plugin", pluginName);
        status.put("version", pluginVersion);
        status.put("status", serverRunning ? "running" : "stopped");
        status.put("port", serverPort);
        status.put("loginWxid", getLoginWxid());
        status.put("loginAlias", getLoginAlias());
        status.put("threadPoolActive", threadPool != null && !threadPool.isShutdown());
        status.put("messageQueueSize", messageQueue.size());
        
        return createSuccess(status);
    } catch (Exception e) {
        log("获取服务器状态失败: " + e);
        return createError("获取状态失败: " + e.getMessage());
    }
}

String handleServerStop() {
    if (!serverRunning) {
        return createError("服务器未运行");
    }
    
    // 异步停止服务器
    new Thread(new Runnable() {
        public void run() {
            try {
                Thread.sleep(500);
                stopServer();
            } catch (Exception e) {
                log("停止服务器异常: " + e);
            }
        }
    }, "Server-Stopper").start();
    
    return createSuccess("服务器将在 0.5 秒后停止");
}

String handleServerRestart() {
    // 异步重启服务器
    new Thread(new Runnable() {
        public void run() {
            try {
                log("准备重启服务器...");
                Thread.sleep(500);
                
                stopServer();
                Thread.sleep(1500);
                
                startServer();
                log("服务器重启完成");
            } catch (Exception e) {
                log("重启服务器失败: " + e);
                toast("重启服务器失败");
            }
        }
    }, "Server-Restarter").start();
    
    return createSuccess("服务器将在 2 秒后重启");
}

// ==================== 工具方法 ====================

void sendResponse(OutputStream output, int code, String type, String content) {
    try {
        byte[] bytes = content.getBytes("UTF-8");
        String header = "HTTP/1.1 " + code + " OK\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Connection: close\r\n\r\n";
        
        output.write(header.getBytes("UTF-8"));
        output.write(bytes);
        output.flush();
    } catch (Exception e) {
        log("发送响应失败: " + e);
    }
}

String createSuccess(Object data) {
    try {
        JSONObject resp = new JSONObject();
        resp.put("code", 200);
        resp.put("success", true);
        resp.put("data", data);
        resp.put("timestamp", System.currentTimeMillis());
        return resp.toString();
    } catch (Exception e) {
        return "{\"code\":500,\"success\":false,\"message\":\"JSON错误\"}";
    }
}

String createError(String msg) {
    try {
        JSONObject resp = new JSONObject();
        resp.put("code", 500);
        resp.put("success", false);
        resp.put("message", msg);
        resp.put("timestamp", System.currentTimeMillis());
        return resp.toString();
    } catch (Exception e) {
        return "{\"code\":500,\"success\":false,\"message\":\"" + msg + "\"}";
    }
}

// ==================== 欢迎页面 ====================

String getWelcomePage() {
    return "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>WAuxiliary HTTP API</title>" +
           "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
           "<link rel='stylesheet' href='https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css'>" +
           "<style>" +
           "* { margin: 0; padding: 0; box-sizing: border-box; }" +
           "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; " +
           "max-width: 1200px; margin: 0 auto; padding: 20px; background: #f5f7fa; color: #333; }" +
           ".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; " +
           "padding: 40px 30px; border-radius: 12px; margin-bottom: 30px; box-shadow: 0 8px 16px rgba(0,0,0,0.1); }" +
           ".header h1 { font-size: 2.5em; margin-bottom: 10px; }" +
           ".header p { opacity: 0.9; font-size: 1.1em; }" +
           ".status-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); " +
           "gap: 20px; margin-bottom: 30px; }" +
           ".status-card { background: white; padding: 25px; border-radius: 10px; " +
           "box-shadow: 0 4px 6px rgba(0,0,0,0.07); }" +
           ".status-card h3 { color: #667eea; margin-bottom: 15px; font-size: 1.2em; }" +
           ".status-item { display: flex; justify-content: space-between; padding: 8px 0; " +
           "border-bottom: 1px solid #f0f0f0; }" +
           ".status-item:last-child { border-bottom: none; }" +
           ".status-label { color: #666; }" +
           ".status-value { font-weight: 600; color: #333; }" +
           ".status-running { color: #52c41a; }" +
           ".status-stopped { color: #f5222d; }" +
           ".api-section { background: white; border-radius: 12px; padding: 30px; " +
           "margin-bottom: 25px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); }" +
           ".api-section h2 { margin-bottom: 25px; color: #333; font-size: 1.8em; " +
           "padding-bottom: 15px; border-bottom: 3px solid #667eea; }" +
           ".api-item { margin: 20px 0; padding: 20px; background: #fafbfc; " +
           "border-radius: 8px; border-left: 4px solid #667eea; }" +
           ".api-header { display: flex; align-items: center; gap: 15px; margin-bottom: 12px; }" +
           ".method { padding: 6px 14px; border-radius: 6px; font-weight: bold; " +
           "font-size: 0.85em; color: white; }" +
           ".method.get { background: #1890ff; }" +
           ".method.post { background: #52c41a; }" +
           ".api-path { font-family: 'Courier New', monospace; font-size: 1.15em; " +
           "color: #333; font-weight: 600; }" +
           ".api-desc { color: #666; margin: 10px 0; line-height: 1.6; }" +
           ".params { margin: 15px 0; padding: 15px; background: white; border-radius: 6px; }" +
           ".params-title { font-weight: 600; margin-bottom: 10px; color: #333; }" +
           ".param { margin: 8px 0; padding-left: 15px; }" +
           ".param-name { font-family: 'Courier New', monospace; background: #e6f7ff; " +
           "padding: 3px 8px; border-radius: 4px; color: #0050b3; }" +
           ".code-section { margin: 15px 0; }" +
           ".code-header { display: flex; justify-content: space-between; align-items: center; " +
           "margin-bottom: 8px; }" +
           ".code-header span { font-weight: 600; color: #555; }" +
           ".copy-btn { background: #667eea; color: white; border: none; padding: 8px 16px; " +
           "border-radius: 6px; cursor: pointer; font-size: 0.9em; transition: all 0.3s; }" +
           ".copy-btn:hover { background: #5568d3; transform: translateY(-2px); }" +
           ".copy-btn.copied { background: #52c41a; }" +
           ".code-block { background: #282c34; color: #abb2bf; padding: 18px; " +
           "border-radius: 8px; font-family: 'Courier New', monospace; font-size: 0.9em; " +
           "overflow-x: auto; line-height: 1.6; }" +
           ".footer { text-align: center; margin-top: 50px; padding: 30px; color: #999; " +
           "border-top: 1px solid #e8e8e8; }" +
           ".highlight { color: #e06c75; }" +
           ".string { color: #98c379; }" +
           "@media (max-width: 768px) { " +
           "  .header h1 { font-size: 1.8em; } " +
           "  .api-header { flex-direction: column; align-items: flex-start; } " +
           "}" +
           "</style></head><body>" +
           
           "<div class='header'>" +
           "<h1><i class='fas fa-server'></i> WAuxiliary HTTP API</h1>" +
           "<p>强大的微信 RESTful API 接口 - 优化版 v" + pluginVersion + "</p>" +
           "</div>" +
           
           "<div class='status-cards'>" +
           "<div class='status-card'>" +
           "<h3><i class='fas fa-info-circle'></i> 服务器状态</h3>" +
           "<div class='status-item'>" +
           "<span class='status-label'>运行状态</span>" +
           "<span class='status-value " + (serverRunning ? "status-running" : "status-stopped") + "'>" +
           (serverRunning ? "运行中" : "已停止") + "</span>" +
           "</div>" +
           "<div class='status-item'>" +
           "<span class='status-label'>监听端口</span>" +
           "<span class='status-value'>" + serverPort + "</span>" +
           "</div>" +
           "<div class='status-item'>" +
           "<span class='status-label'>消息队列</span>" +
           "<span class='status-value'>" + messageQueue.size() + " 条</span>" +
           "</div>" +
           "</div>" +
           
           "<div class='status-card'>" +
           "<h3><i class='fas fa-user-circle'></i> 登录信息</h3>" +
           "<div class='status-item'>" +
           "<span class='status-label'>微信ID</span>" +
           "<span class='status-value'>" + getLoginWxid() + "</span>" +
           "</div>" +
           "<div class='status-item'>" +
           "<span class='status-label'>微信号</span>" +
           "<span class='status-value'>" + getLoginAlias() + "</span>" +
           "</div>" +
           "</div>" +
           
           "<div class='status-card'>" +
           "<h3><i class='fas fa-cog'></i> 系统信息</h3>" +
           "<div class='status-item'>" +
           "<span class='status-label'>插件版本</span>" +
           "<span class='status-value'>" + pluginVersion + "</span>" +
           "</div>" +
           "<div class='status-item'>" +
           "<span class='status-label'>API地址</span>" +
           "<span class='status-value'>http://localhost:" + serverPort + "</span>" +
           "</div>" +
           "</div>" +
           "</div>" +
           
           "<div class='api-section'>" +
           "<h2><i class='fas fa-users'></i> 联系人接口</h2>" +
           
           "<div class='api-item'>" +
           "<div class='api-header'>" +
           "<span class='method get'>GET</span>" +
           "<span class='api-path'>/api/contacts/friends</span>" +
           "</div>" +
           "<div class='api-desc'>获取好友列表，包含微信ID、昵称、备注等信息</div>" +
           "<div class='code-section'>" +
           "<div class='code-header'>" +
           "<span>示例请求：</span>" +
           "<button class='copy-btn' onclick='copyCode(this, \"curl1\")'>复制</button>" +
           "</div>" +
           "<pre class='code-block' id='curl1'>curl -X GET \"http://localhost:" + serverPort + "/api/contacts/friends\"</pre>" +
           "</div>" +
           "</div>" +
           
           "<div class='api-item'>" +
           "<div class='api-header'>" +
           "<span class='method get'>GET</span>" +
           "<span class='api-path'>/api/contacts/groups</span>" +
           "</div>" +
           "<div class='api-desc'>获取群组列表，包含群ID、群名称、成员数量等信息</div>" +
           "<div class='code-section'>" +
           "<div class='code-header'>" +
           "<span>示例请求：</span>" +
           "<button class='copy-btn' onclick='copyCode(this, \"curl2\")'>复制</button>" +
           "</div>" +
           "<pre class='code-block' id='curl2'>curl -X GET \"http://localhost:" + serverPort + "/api/contacts/groups\"</pre>" +
           "</div>" +
           "</div>" +
           
           "<div class='api-item'>" +
           "<div class='api-header'>" +
           "<span class='method get'>GET</span>" +
           "<span class='api-path'>/api/user/info</span>" +
           "</div>" +
           "<div class='api-desc'>获取当前登录用户信息</div>" +
           "<div class='code-section'>" +
           "<div class='code-header'>" +
           "<span>示例请求：</span>" +
           "<button class='copy-btn' onclick='copyCode(this, \"curl3\")'>复制</button>" +
           "</div>" +
           "<pre class='code-block' id='curl3'>curl -X GET \"http://localhost:" + serverPort + "/api/user/info\"</pre>" +
           "</div>" +
           "</div>" +
           "</div>" +
           
           "<div class='api-section'>" +
           "<h2><i class='fas fa-paper-plane'></i> 消息发送接口</h2>" +
           
           "<div class='api-item'>" +
           "<div class='api-header'>" +
           "<span class='method post'>POST</span>" +
           "<span class='api-path'>/api/message/sendText</span>" +
           "</div>" +
           "<div class='api-desc'>发送文本消息到指定用户或群组</div>" +
           "<div class='params'>" +
           "<div class='params-title'>参数：</div>" +
           "<div class='param'><span class='param-name'>wxid</span> - 接收者微信ID或群ID（必填）</div>" +
           "<div class='param'><span class='param-name'>content</span> - 消息内容（必填）</div>" +
           "</div>" +
           "<div class='code-section'>" +
           "<div class='code-header'>" +
           "<span>示例请求：</span>" +
           "<button class='copy-btn' onclick='copyCode(this, \"curl4\")'>复制</button>" +
           "</div>" +
           "<pre class='code-block' id='curl4'>curl -X POST \"http://localhost:" + serverPort + "/api/message/sendText\" \\\n" +
           "  -d \"wxid=<span class='highlight'>wxid_xxxxx</span>&content=<span class='string'>你好</span>\"</pre>" +
           "</div>" +
           "</div>" +
           
           "<div class='api-item'>" +
           "<div class='api-header'>" +
           "<span class='method post'>POST</span>" +
           "<span class='api-path'>/api/message/sendImage</span>" +
           "</div>" +
           "<div class='api-desc'>发送图片消息（支持 jpg、png、gif 等格式）</div>" +
           "<div class='params'>" +
           "<div class='params-title'>参数：</div>" +
           "<div class='param'><span class='param-name'>wxid</span> - 接收者微信ID（必填）</div>" +
           "<div class='param'><span class='param-name'>path</span> - 图片文件路径（必填）</div>" +
           "</div>" +
           "<div class='code-section'>" +
           "<div class='code-header'>" +
           "<span>示例请求：</span>" +
           "<button class='copy-btn' onclick='copyCode(this, \"curl5\")'>复制</button>" +
           "</div>" +
           "<pre class='code-block' id='curl5'>curl -X POST \"http://localhost:" + serverPort + "/api/message/sendImage\" \\\n" +
           "  -d \"wxid=<span class='highlight'>wxid_xxxxx</span>&path=<span class='string'>/sdcard/Download/image.jpg</span>\"</pre>" +
           "</div>" +
           "</div>" +
           
           "<div class='api-item'>" +
           "<div class='api-header'>" +
           "<span class='method post'>POST</span>" +
           "<span class='api-path'>/api/message/sendVideo</span>" +
           "</div>" +
           "<div class='api-desc'>发送视频消息（支持 mp4、avi、mov、3gp 格式）</div>" +
           "<div class='params'>" +
           "<div class='params-title'>参数：</div>" +
           "<div class='param'><span class='param-name'>wxid</span> - 接收者微信ID（必填）</div>" +
           "<div class='param'><span class='param-name'>path</span> - 视频文件路径（必填）</div>" +
           "</div>" +
           "<div class='code-section'>" +
           "<div class='code-header'>" +
           "<span>示例请求：</span>" +
           "<button class='copy-btn' onclick='copyCode(this, \"curl6\")'>复制</button>" +
           "</div>" +
           "<pre class='code-block' id='curl6'>curl -X POST \"http://localhost:" + serverPort + "/api/message/sendVideo\" \\\n" +
           "  -d \"wxid=<span class='highlight'>wxid_xxxxx</span>&path=<span class='string'>/sdcard/Download/video.mp4</span>\"</pre>" +
           "</div>" +
           "</div>" +
           
           "<div class='api-item'>" +
           "<div class='api-header'>" +
           "<span class='method post'>POST</span>" +
           "<span class='api-path'>/api/message/sendFile</span>" +
           "</div>" +
           "<div class='api-desc'>发送文件消息（支持所有文件类型）</div>" +
           "<div class='params'>" +
           "<div class='params-title'>参数：</div>" +
           "<div class='param'><span class='param-name'>wxid</span> - 接收者微信ID（必填）</div>" +
           "<div class='param'><span class='param-name'>path</span> - 文件路径（必填）</div>" +
           "</div>" +
           "<div class='code-section'>" +
           "<div class='code-header'>" +
           "<span>示例请求：</span>" +
           "<button class='copy-btn' onclick='copyCode(this, \"curl7\")'>复制</button>" +
           "</div>" +
           "<pre class='code-block' id='curl7'>curl -X POST \"http://localhost:" + serverPort + "/api/message/sendFile\" \\\n" +
           "  -d \"wxid=<span class='highlight'>wxid_xxxxx</span>&path=<span class='string'>/sdcard/Download/document.pdf</span>\"</pre>" +
           "</div>" +
           "</div>" +
           "</div>" +
           
           "<div class='api-section'>" +
           "<h2><i class='fas fa-tools'></i> 系统管理接口</h2>" +
           
           "<div class='api-item'>" +
           "<div class='api-header'>" +
           "<span class='method get'>GET</span>" +
           "<span class='api-path'>/api/server/status</span>" +
           "</div>" +
           "<div class='api-desc'>获取服务器详细状态信息</div>" +
           "<div class='code-section'>" +
           "<div class='code-header'>" +
           "<span>示例请求：</span>" +
           "<button class='copy-btn' onclick='copyCode(this, \"curl8\")'>复制</button>" +
           "</div>" +
           "<pre class='code-block' id='curl8'>curl -X GET \"http://localhost:" + serverPort + "/api/server/status\"</pre>" +
           "</div>" +
           "</div>" +
           
           "<div class='api-item'>" +
           "<div class='api-header'>" +
           "<span class='method get'>GET</span>" +
           "<span class='api-path'>/api/server/logs</span>" +
           "</div>" +
           "<div class='api-desc'>查看服务器运行日志（最近500行）</div>" +
           "<div class='code-section'>" +
           "<div class='code-header'>" +
           "<span>示例请求：</span>" +
           "<button class='copy-btn' onclick='copyCode(this, \"curl9\")'>复制</button>" +
           "</div>" +
           "<pre class='code-block' id='curl9'>curl -X GET \"http://localhost:" + serverPort + "/api/server/logs\"</pre>" +
           "</div>" +
           "</div>" +
           "</div>" +
           
           "<div class='footer'>" +
           "<p><strong>WAuxiliary HTTP API</strong> - 优化版 v" + pluginVersion + "</p>" +
           "<p style='margin-top: 10px; color: #bbb;'>修复问题：视频发送、线程管理、资源泄漏</p>" +
           "<p style='margin-top: 5px; color: #ccc;'>作者: " + pluginAuthor + "</p>" +
           "</div>" +
           
           "<script>" +
           "function copyCode(btn, id) {" +
           "  const code = document.getElementById(id).textContent;" +
           "  const temp = document.createElement('textarea');" +
           "  temp.value = code;" +
           "  document.body.appendChild(temp);" +
           "  temp.select();" +
           "  document.execCommand('copy');" +
           "  document.body.removeChild(temp);" +
           "  " +
           "  const originalText = btn.textContent;" +
           "  btn.textContent = '已复制!';" +
           "  btn.classList.add('copied');" +
           "  setTimeout(() => {" +
           "    btn.textContent = originalText;" +
           "    btn.classList.remove('copied');" +
           "  }, 2000);" +
           "}" +
           "</script>" +
           "</body></html>";
}