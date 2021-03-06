package org.lpw.tephra.weixin;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.lpw.tephra.bean.BeanFactory;
import org.lpw.tephra.bean.ContextRefreshedListener;
import org.lpw.tephra.cache.Cache;
import org.lpw.tephra.crypto.Digest;
import org.lpw.tephra.scheduler.HourJob;
import org.lpw.tephra.util.Context;
import org.lpw.tephra.util.Converter;
import org.lpw.tephra.util.DateTime;
import org.lpw.tephra.util.Generator;
import org.lpw.tephra.util.Http;
import org.lpw.tephra.util.Io;
import org.lpw.tephra.util.Logger;
import org.lpw.tephra.util.TimeUnit;
import org.lpw.tephra.util.Validator;
import org.lpw.tephra.weixin.gateway.PayGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

/**
 * @author lpw
 */
@Service("tephra.weixin.helper")
public class WeixinHelperImpl implements WeixinHelper, HourJob, ContextRefreshedListener {
    private static final String CACHE_JSON = "tephra.weixin.helper.json:";

    @Inject
    private Cache cache;
    @Inject
    private Digest digest;
    @Inject
    private Http http;
    @Inject
    private Converter converter;
    @Inject
    private DateTime dateTime;
    @Inject
    private Generator generator;
    @Inject
    private Context context;
    @Inject
    private Validator validator;
    @Inject
    private Io io;
    @Inject
    private Logger logger;
    @Value("${tephra.ctrl.service-root:}")
    private String root;
    @Value("${tephra.weixin.config:}")
    private String config;
    @Value("${tephra.weixin.refresh:false}")
    private boolean refresh;
    private Map<String, WeixinConfig> configs;
    private String[] appIds;
    private Map<String, PayGateway> gateways;

    @Override
    public String createQrCode(String appId, int id, int expire, String sceneStr) {
        if (id < 0) {
            logger.warn(null, "二维码ID[{}]小于0！", id);

            return null;
        }

        JSONObject object = new JSONObject();
        object.put("action_name", expire > 0 ? "QR_SCENE" : "QR_LIMIT_STR_SCENE");
        JSONObject info = new JSONObject();
        JSONObject scene = new JSONObject();
        if (expire > 0) {
            object.put("expire_seconds", expire);
            scene.put("scene_id", id);
        } else
            scene.put("scene_str", id > 0 ? id : sceneStr);
        info.put("scene", scene);
        object.put("action_info", info);

        JSONObject result = JSON.parseObject(http.post("https://api.weixin.qq.com/cgi-bin/qrcode/create?access_token=" + getToken(appId), null, object.toString()));
        if (logger.isDebugEnable())
            logger.debug("创建微信二维码[{}]。", result);

        return result.containsKey("ticket") ? ("https://mp.weixin.qq.com/cgi-bin/showqrcode?ticket=" + converter.encodeUrl(result.getString("ticket"), null)) : null;
    }

    @Override
    public String getRedirectUrl(String appId, String uri) {
        if (validator.isEmpty(appId))
            appId = getAppId(0);

        return "https://open.weixin.qq.com/connect/oauth2/authorize?appid=" + appId + "&redirect_uri="
                + converter.encodeUrl(root + WeixinService.URI + appId + "?redirect=" + uri, null)
                + "&response_type=code&scope=snsapi_userinfo&state=STATE#wechat_redirect";
    }

    @Override
    public JSONObject getUserInfo(String appId, String openId) {
        if (validator.isEmpty(openId))
            return null;

        Map<String, String> map = new HashMap<>();
        map.put("access_token", getToken(appId));
        map.put("openid", openId);
        map.put("lang", "zh_CN");

        return JSON.parseObject(http.get("https://api.weixin.qq.com/sns/userinfo", null, map));
    }

    @Override
    public JSONObject getJsApiSign(String appId, String url) {
        JSONObject object = new JSONObject();
        object.put("nonceStr", generator.random(32));
        object.put("timestamp", System.currentTimeMillis() / 1000);
        object.put("url", url);
        object.put("signature", digest.sha1("jsapi_ticket=" + getJsapiTicket(appId)
                + "&noncestr=" + object.getString("nonceStr") + "&timestamp=" + object.getLong("timestamp") + "&url=" + url));

        return object;
    }

    @Override
    public String upload(String appId, String type, String uri) {
        File file = new File(context.getAbsolutePath(uri));
        if (!file.exists()) {
            logger.warn(null, "要上传到微信临时素材区的媒体文件[{}]不存在！", uri);

            return null;
        }

        Map<String, File> map = new HashMap<>();
        map.put("media", file);
        JSONObject result = JSON.parseObject(http.upload("https://api.weixin.qq.com/cgi-bin/media/upload?access_token=" + getToken(appId) + "&type=" + type, null, null, map));
        if (result.containsKey("errcode")) {
            logger.warn(null, "上传媒体文件[{}]到微信临时素材区失败[{}]！", uri, result);

            return null;
        }

        return result.getString("thumb".equals(type) ? "thumb_media_id" : "media_id");
    }

    @Override
    public String download(String appId, String mediaId, Timestamp time, boolean https) {
        String name = generator.random(32);
        String temp = context.getAbsolutePath("/upload/weixin/" + name);
        Map<String, String> map = http.download((https ? "https" : "http") + "://api.weixin.qq.com/cgi-bin/media/get?access_token=" + getToken(appId) + "&media_id=" + mediaId, null, "", temp);
        if (map == null) {
            io.delete(temp);

            return null;
        }

        String disposition = map.get("Content-disposition");
        String uri = "/upload/" + map.get("Content-Type") + "/weixin/" + dateTime.toString(time, "yyyyMMdd")
                + "/" + name + disposition.substring(disposition.lastIndexOf('.'), disposition.length() - 1);

        try {
            String path = context.getAbsolutePath(uri);
            io.mkdirs(path.substring(0, path.lastIndexOf('/')));
            io.move(temp, path);
        } catch (IOException e) {
            logger.warn(e, "移动文件时发生异常！");
        }

        return uri;
    }

    @Override
    public String getToken(String appId) {
        String token = fromCache(appId, "token");
        if (!validator.isEmpty(token))
            return token;

        WeixinConfig config = getConfig(appId);

        return config == null ? null : config.getCurrentToken();
    }

    @Override
    public String getJsapiTicket(String appId) {
        String ticket = fromCache(appId, "ticket");
        if (!validator.isEmpty(ticket))
            return ticket;

        WeixinConfig config = getConfig(appId);

        return config == null ? null : config.getJsapiTicket();
    }

    private String fromCache(String appId, String key) {
        for (int i = 0; i < 2; i++) {
            JSONObject object = cache.get(getCacheKey(appId, i));
            if (object != null)
                return object.getString(key);
        }

        return null;
    }

    private String getCacheKey(String appId, int hour) {
        return CACHE_JSON + (System.currentTimeMillis() / TimeUnit.Hour.getTime() - hour) + ":" + appId;
    }

    @Override
    public void executeHourJob() {
        refreshToken();
    }

    @Override
    public PayGateway getPayGateway(String type) {
        if (!gateways.containsKey(type)) {
            logger.warn(null, "微信支付类型[{}]不存在！", type);

            return null;
        }

        return gateways.get(type);
    }

    @Override
    public WeixinConfig getConfig(String appId) {
        if (validator.isEmpty(appId))
            appId = getAppId(0);

        if (!configs.containsKey(appId)) {
            logger.warn(null, "无法获得微信[AppId:{}]配置！", appId);

            return null;
        }

        return configs.get(appId);
    }

    @Override
    public String getAppId(int index) {
        return index < 0 || index >= appIds.length ? null : appIds[index];
    }

    @Override
    public int getContextRefreshedSort() {
        return 31;
    }

    @Override
    public void onContextRefreshed() {
        if (validator.isEmpty(config))
            return;

        configs = new HashMap<>();
        JSONArray array = JSON.parseArray(config);
        appIds = new String[array.size()];
        for (int i = 0; i < array.size(); i++) {
            JSONObject object = array.getJSONObject(i);
            WeixinConfig config = new WeixinConfig();
            config.setAppId(object.getString("appId"));
            config.setSecret(object.getString("secret"));
            config.setToken(object.getString("token"));
            if (object.containsKey("mchId"))
                config.setMchId(object.getString("mchId"));
            if (object.containsKey("mchKey"))
                config.setMchKey(object.getString("mchKey"));
            configs.put(config.getAppId(), config);
            appIds[i] = config.getAppId();
        }
        refreshToken();
        if (logger.isDebugEnable())
            logger.debug("配置微信公众号{}完成。", configs);

        gateways = new HashMap<>();
        BeanFactory.getBeans(PayGateway.class).forEach(gateway -> gateways.put(gateway.getType(), gateway));
    }

    private void refreshToken() {
        if (!refresh || validator.isEmpty(configs))
            return;

        configs.values().forEach(config -> {
            JSONObject json = new JSONObject();
            JSONObject object = JSON.parseObject(http.get("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=" + config.getAppId() + "&secret=" + config.getSecret(), null, ""));
            if (object != null && object.containsKey("access_token")) {
                config.setCurrentToken(object.getString("access_token"));
                json.put("token", config.getCurrentToken());
                if (logger.isInfoEnable())
                    logger.info("获取微信公众号Token[{}:{}]。", config.getAppId(), config.getCurrentToken());
            } else
                logger.warn(null, "获取微信公众号Token[{}]失败！", object);

            object = JSON.parseObject(http.get("https://api.weixin.qq.com/cgi-bin/ticket/getticket?type=jsapi&access_token=" + config.getCurrentToken(), null, ""));
            if (object != null && object.containsKey("ticket")) {
                config.setJsapiTicket(object.getString("ticket"));
                json.put("ticket", config.getJsapiTicket());
                if (logger.isInfoEnable())
                    logger.info("获取微信公众号JSAPI Ticket[{}:{}]。", config.getAppId(), config.getCurrentToken());
            } else
                logger.warn(null, "获取微信公众号JSAPI Ticket[{}]失败！", object);

            if (json.isEmpty())
                cache.put(getCacheKey(config.getAppId(), 0), json, false);
        });
    }
}
