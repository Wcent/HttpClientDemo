package org.cent.HttpClientDemo.util;

import com.alibaba.fastjson.JSON;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOReactor;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 封装发送http get/post同步请求工具类
 * 使用完毕需要释放连接池资源或通过try捕获使用并最后自动释放
 * @author Vincent
 * @version 1.0 2019/11/17
 */
public class MyHttpAsyncClientUtil implements AutoCloseable {

    static private CloseableHttpAsyncClient httpAsyncClient;
    // 请求连接池被引用次数，释放资源依据
    static private AtomicInteger atomicInteger = new AtomicInteger(0);

    /**
     * 初始化请求客户端类，并启用，及累计引用次数
     */
    public MyHttpAsyncClientUtil() {

        // 自增累计引用数，在自动关闭时判断无引用数时可释放链接池资源
        atomicInteger.incrementAndGet();

        // 已初始化时复用（前置不加锁判断优化性能）
        if (httpAsyncClient != null) {
            return;
        }

        // 加锁后重复判断，基于并发线程安全考虑，确保前置判断后未有其他线程完成初始化
        synchronized (MyHttpAsyncClientUtil.class) {
            if (httpAsyncClient != null) {
                return;
            }

            // 获取默认客户端请求链接池类
//            httpAsyncClient = HttpAsyncClients.createDefault();

            // 定制请求连接配置类
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(60000) // 连接超时，建立连接时间（三次TCP握手完成时间）
                    .setSocketTimeout(60000) // 读取超时（请求超时），数据传输过程中数据包之间间隔的最大时间
                    .setConnectionRequestTimeout(3000) // 使用连接池管理连接，从连接池获取连接超时时间
                    .build();

            // 配置连接io线程
            IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
                    .setIoThreadCount(Runtime.getRuntime().availableProcessors())
                    .setSoKeepAlive(true)
                    .build();
            ConnectingIOReactor ioReactor = null;
            try {
                ioReactor = new DefaultConnectingIOReactor(ioReactorConfig);
            } catch (IOReactorException e) {
                e.printStackTrace();
            }

            // 定制客户端请求链接池管理类
            PoolingNHttpClientConnectionManager connectionManager = new PoolingNHttpClientConnectionManager(ioReactor);
            connectionManager.setMaxTotal(100); // 连接池最大连接数
            connectionManager.setDefaultMaxPerRoute(100); // 每路最大连接数（同一路由最大并发连接数）

            httpAsyncClient = HttpAsyncClients.custom()
                    .setConnectionManager(connectionManager)
                    .setDefaultRequestConfig(requestConfig)
                    .build();

            // 异步需要启用
            httpAsyncClient.start();
        }
    }

    /**
     * 重载以支持try自动释放资源机制
     */
    @Override
    public void close() {

        // 自减请求连接引用数，在无引用后方可释放链接池资源（前置不加锁判断优化性能）
        if (atomicInteger.decrementAndGet() > 0) {
            return;
        }

        // 加锁后重复判断，基于并发线程安全考虑，确保前置判断后未有其他线程新引用资源
        synchronized (MyHttpAsyncClientUtil.class) {
            if (atomicInteger.get() > 0) {
                return;
            }

            if (httpAsyncClient != null) {
                try {
                    httpAsyncClient.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            // 关闭链接池后要主动设置请求客户端为null，避免对象未被回收导致不能正常重新初始化
            httpAsyncClient = null;
        }
    }

    /**
     * 发送http get异步请求
     * @param url 协议+主机+端口+路径
     * @param headers 请求头
     * @param queryParams 查询参数（条件）
     */
    public void get(String url, Map<String, String> headers, Map<String, String> queryParams) {

        HttpGet httpGet = null;
        try {
            URIBuilder uriBuilder = new URIBuilder(url);
            if (queryParams != null) {
                for (Map.Entry<String, String> param : queryParams.entrySet()) {
                    uriBuilder.addParameter(param.getKey(), param.getValue());
                }
            }
            URI uri = uriBuilder.build();
            System.out.println(uri);

            httpGet = new HttpGet(uri);
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    httpGet.addHeader(header.getKey(), header.getValue());
                }
            }
        } catch (URISyntaxException e) {
            e.printStackTrace();
            return;
        }

        // 链接池发送异步请求，非阻塞继续处理，IO请求完成由OS通知回调处理
        httpAsyncClient.execute(httpGet, new FutureCallback<HttpResponse>() {
            @Override
            public void completed(HttpResponse result) {
                if (result.getStatusLine().getStatusCode() == 200) {
                    try {
                        System.out.println(EntityUtils.toString(result.getEntity(), "utf-8"));
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                } else {
                    System.out.println(result.getStatusLine().getStatusCode());
                    System.out.println(result.getStatusLine().getReasonPhrase());
                }
            }

            @Override
            public void failed(Exception ex) {
                ex.printStackTrace();
            }

            @Override
            public void cancelled() {
            }
        });
    }

    /**
     * 发送http post异步请求，请求体为普通字符串等（通用）
     * @param url 协议+主机+端口+路径
     * @param headers 请求头
     * @param entity 请求体（body），普通字符串原样写入
     */
    public void post(String url, Map<String, String> headers, String entity) {
        if (headers == null) {
            headers = new HashMap<>();
            headers.put("content-type", "text/plain; charset=UTF-8");
        }
        _post(url, headers, null, entity);
    }

    /**
     * 发送http post异步请求，请求体为表单格式内容（key1=value1&key2=value2）
     * @param url 协议+主机+端口+路径
     * @param headers 请求头
     * @param params 请求体（body），传入map最后打包成表单格式字符串
     */
    public void postForm(String url, Map<String, String> headers, Map<String, String> params) {

        if (headers == null) {
            headers = new HashMap<>();
            headers.put("content-type", "application/x-www-form-urlencoded; charset=UTF-8");
        }
        _post(url, headers, params, null);
    }

    /**
     * 发送http post异步请求，请求体为xml报文
     * @param url 协议+主机+端口+路径
     * @param headers 请求头
     * @param xml 请求体（body），xml报文字符串
     */
    public void postXml(String url, Map<String, String> headers, String xml) {

        if (headers == null) {
            headers = new HashMap<>();
            headers.put("content-type", "text/html; charset=UTF-8");
        }
        _post(url, headers, null, xml);
    }

    /**
     * 发送http post异步请求，请求体为json报文
     * @param url 协议+主机+端口+路径
     * @param headers 请求头
     * @param json 请求体（body），json报文字符串
     */
    public void postJson(String url, Map<String, String> headers, String json) {

        if (headers == null) {
            headers = new HashMap<>();
            headers.put("content-type", "application/json; charset=UTF-8");
        }
        _post(url, headers, null, json);
    }

    /**
     * 发送http post请求统一处理方法（内部使用）
     * 注意表单参数与请求实体同时存在时，先设置body的表单内容会被后设置的实体内容覆盖
     * @param url 协议+主机+端口+路径
     * @param headers 请求头
     * @param params 请求体（表单参数）
     * @param entity 请求体（xml/json/其他普通字符串）
     */
    private void _post(String url, Map<String, String> headers, Map<String, String> params, String entity) {

        HttpPost httpPost = new HttpPost(url);

        // 请求头
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                httpPost.addHeader(header.getKey(), header.getValue());
            }
        }

        // 请求体表单参数
        if (params != null) {
            try {
                List<NameValuePair> pairList = new ArrayList<>();
                for (Map.Entry<String, String> param : params.entrySet()) {
                    pairList.add(new BasicNameValuePair(param.getKey(), param.getValue()));
                }
                UrlEncodedFormEntity formEntity = new UrlEncodedFormEntity(pairList);
                httpPost.setEntity(formEntity);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
                return;
            }
        }

        // 请求体xml/json/其他普通字符串内容，后设置所以会覆盖前设置body的表单内容
        if (entity != null) {
            httpPost.setEntity(new StringEntity(entity, "utf-8"));
        }

        // 链接池发送异步请求，非阻塞继续处理，IO请求完成由OS通知回调处理
        httpAsyncClient.execute(httpPost, new FutureCallback<HttpResponse>() {
            @Override
            public void completed(HttpResponse result) {
                if (result.getStatusLine().getStatusCode() == 200) {
                    try {
                        System.out.println(EntityUtils.toString(result.getEntity(), "utf-8"));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    System.out.println(result.getStatusLine().getStatusCode());
                    System.out.println(result.getStatusLine().getReasonPhrase());
                }
            }

            @Override
            public void failed(Exception ex) {
                ex.printStackTrace();
            }

            @Override
            public void cancelled() {

            }
        });
    }

    public static void main(String[] args) {

        try(MyHttpAsyncClientUtil myHttpAsyncClientUtil = new MyHttpAsyncClientUtil()) {

            Map<String, String> headers = new HashMap<>();
            Map<String, String> params = new HashMap<>();

            myHttpAsyncClientUtil.get("https://www.baidu.com/", null, null);

            headers.put("user-agent",
                    "Mozilla/5.0 (Windows NT 6.3; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/50.0.2661.94 Safari/537.36");
            params.put("scope", "all");
            params.put("q", "java");
            myHttpAsyncClientUtil.postForm("https://www.oschina.net/search", headers, params);

            params.clear();
            params.put("content-type", "json");
            params.put("method", "post");
            myHttpAsyncClientUtil.postJson("http://localhost:8080/post-json", null, JSON.toJSONString(params));
            myHttpAsyncClientUtil.postForm("http://localhost:8080/post-string", null, params);
            myHttpAsyncClientUtil.post("http://localhost:8080/post-string", null, "hello world!");

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
