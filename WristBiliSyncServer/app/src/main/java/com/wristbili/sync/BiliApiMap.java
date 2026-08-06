package com.wristbili.sync;

import android.net.Uri;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 手环语义化请求的 API 映射表（架构：请求 URL 整合在手机端）。
 *
 * 手环页面只发 {@code {tag:'fetch', action:'xxx', params:{...}}} 指令，
 * 手机端按 action 查表组装真实请求 URL（含登录 Cookie 注入、POST csrf 补齐），
 * 请求完成后把与直传 url 完全一致的响应数据回传手环 —— 手环渲染层零改动。
 *
 * urlTemplates 支持多个（依次尝试，任一 HTTP 2xx 且 body 非空即采用），
 * 用于首页直播列表、搜索等原本在手环端多接口重试的场景。
 * 未收录的接口仍支持旧 url 直传兜底。
 */
public final class BiliApiMap {

    private BiliApiMap() {
    }

    /** 单个接口定义：HTTP 方法 + URL 模板列表（{param} 占位符会被 params 替换，其余 params 追加为 query） */
    static final class ApiDef {
        final String method;
        final String[] urlTemplates;

        ApiDef(String method, String... urlTemplates) {
            this.method = method;
            this.urlTemplates = urlTemplates;
        }
    }

    private static final Map<String, ApiDef> MAP = new HashMap<>();

    static {
        // ===== 首页 =====
        // 个性化推荐（wbi 签名由手机端附加）；HTTP 层失败时自动兜底热门
        // log42：ps=5 —— 首页推荐 5 个一页分页（手环端共 4 页 20 卡，refresh 翻页 1→4）
        MAP.put("home_rcmd", new ApiDef("GET",
                "https://api.bilibili.com/x/web-interface/index/top/feed/rcmd?ps=5&fresh_type=4&fresh_idx={page}",
                "https://api.bilibili.com/x/web-interface/popular?ps=5&pn={page}"));
        MAP.put("home_popular", new ApiDef("GET",
                "https://api.bilibili.com/x/web-interface/popular?ps=5&pn={page}"));
        // 直播列表：4 个接口依次尝试（原手环端重试逻辑搬到手机端）
        MAP.put("home_live_list", new ApiDef("GET",
                "https://api.live.bilibili.com/room/v2/Area/getRoomList?parent_area_id=0&area_id=0&sort_type=online&page=1&page_size=10",
                "https://api.live.bilibili.com/room/v1/Area/getRoomList?parent_area_id=0&area_id=0&sort_type=online&page=1&page_size=10",
                "https://api.live.bilibili.com/xlive/web-interface/v1/second/getList?platform=web&parent_area_id=0&area_id=0&page=1",
                "https://api.live.bilibili.com/room/v1/AppIndex/getAllList?platform=ios&device=phone"));

        // ===== 我的 =====
        MAP.put("nav", new ApiDef("GET",
                "https://api.bilibili.com/x/web-interface/nav"));
        MAP.put("relation_stat", new ApiDef("GET",
                "https://api.bilibili.com/x/relation/stat?vmid={vmid}"));

        // ===== 视频 =====
        MAP.put("video_view", new ApiDef("GET",
                "https://api.bilibili.com/x/web-interface/view?bvid={bvid}"));
        MAP.put("video_videoshot", new ApiDef("GET",
                "https://api.bilibili.com/x/player/videoshot?bvid={bvid}&cid={cid}&index=1"));
        MAP.put("video_playurl", new ApiDef("GET",
                "https://api.bilibili.com/x/player/playurl?bvid={bvid}&cid={cid}&qn={qn}&fnval=0&fourk=0"));
        MAP.put("video_reply", new ApiDef("GET",
                "https://api.bilibili.com/x/v2/reply?type=1&oid={oid}&sort=1&ps={ps}&pn={pn}"));
        // 写操作（点赞/投币/收藏/关注：V2 与 Web 独立 action，body 由手环原样传，csrf 由手机端补齐）
        MAP.put("video_like_v2", new ApiDef("POST",
                "https://app.bilibili.com/x/v2/view/like"));
        MAP.put("video_like_web", new ApiDef("POST",
                "https://api.bilibili.com/x/web-interface/archive/like"));
        MAP.put("video_coin_v2", new ApiDef("POST",
                "https://app.bilibili.com/x/v2/view/coin/add"));
        MAP.put("video_coin_web", new ApiDef("POST",
                "https://api.bilibili.com/x/web-interface/coin/add"));
        MAP.put("video_fav_deal", new ApiDef("POST",
                "https://api.bilibili.com/x/v3/fav/resource/deal"));
        MAP.put("relation_modify", new ApiDef("POST",
                "https://api.bilibili.com/x/relation/modify"));

        // ===== 动态 =====
        MAP.put("dynamic_feed", new ApiDef("GET",
                "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?timezone_offset=-480&type=all&page={page}"));

        // ===== 直播 =====
        MAP.put("live_room", new ApiDef("GET",
                "https://api.live.bilibili.com/room/v1/Room/get_info?room_id={room_id}"));

        // ===== 热榜 / 搜索 =====
        MAP.put("hot_search", new ApiDef("GET",
                "https://api.bilibili.com/x/web-interface/search/square?limit={limit}"));
        MAP.put("search", new ApiDef("GET",
                "https://api.bilibili.com/x/web-interface/search/type?search_type={search_type}&keyword={kw}&page=1&page_size=8"));
        MAP.put("search_v2", new ApiDef("GET",
                "https://api.bilibili.com/x/web-interface/search/all/v2?keyword={kw}&page=1&page_size=8"));

        // ===== UP主空间 =====
        MAP.put("up_card", new ApiDef("GET",
                "https://api.bilibili.com/x/web-interface/card?mid={mid}"));

        // ===== 评论 =====
        MAP.put("post_comment_web", new ApiDef("POST",
                "https://api.bilibili.com/x/v2/reply/add"));
        MAP.put("post_comment_app", new ApiDef("POST",
                "https://app.bilibili.com/x/v2/reply/add"));

        // ===== 收藏 / 历史 =====
        MAP.put("fav_folder_list", new ApiDef("GET",
                "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid={up_mid}&type=2"));
        MAP.put("fav_resource_list", new ApiDef("GET",
                "https://api.bilibili.com/x/v3/fav/resource/list?media_id={media_id}&pn=1&ps=20&platform=web&type=0"));
        MAP.put("history_cursor", new ApiDef("GET",
                "https://api.bilibili.com/x/web-interface/history/cursor?max={max}&view_at=0&business=archive"));

        // ===== 私信 =====
        MAP.put("msg_sessions_v2", new ApiDef("GET",
                "https://api.vc.bilibili.com/session_svr/v2/session_svr/get_sessions?session_type=1&group_fold=1&unfollow_fold=0&sort_rule=2"));
        MAP.put("msg_sessions_v1", new ApiDef("GET",
                "https://api.vc.bilibili.com/session_svr/v1/session_svr/get_sessions?session_type=1&group_fold=1&unfollow_fold=0&sort_rule=2"));
        MAP.put("msg_send", new ApiDef("POST",
                "https://api.vc.bilibili.com/web_im/v1/web_im/send_msg"));
        MAP.put("space_acc", new ApiDef("GET",
                "https://api.bilibili.com/x/space/acc/info?mid={mid}"));

        // ===== AI 总结（第三方）=====
        MAP.put("video_page", new ApiDef("GET",
                "https://www.bilibili.com/video/{bvid}"));
        MAP.put("ai_summary_bili", new ApiDef("GET",
                "https://api.bilibili.com/x/web-interface/view/conclusion/get?bvid={bvid}&cid={cid}"));
        MAP.put("ai_summary_quark", new ApiDef("POST",
                "https://quark.sm.cn/api/rest?method=ai.video.summary"));
        MAP.put("ai_summary_ark", new ApiDef("POST",
                "https://ark.cn-beijing.volces.com/api/v3/chat/completions"));
        MAP.put("ai_summary_dashscope", new ApiDef("POST",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"));

        // ===== 漫画（第三方）=====
        MAP.put("manga_home", new ApiDef("GET",
                "https://apis.netstart.cn/bcomic/HomeFeed?pageNum=1&pageSize=4"));
        MAP.put("manga_search", new ApiDef("GET",
                "https://apis.netstart.cn/bcomic/Search?key_word={kw}&page_num=1&page_size=4"));
        MAP.put("manga_detail", new ApiDef("GET",
                "https://apis.netstart.cn/bcomic/ComicDetail?comic_id={comic_id}"));
        MAP.put("manga_images", new ApiDef("GET",
                "https://apis.netstart.cn/bcomic/GetImageIndex?ep_id={ep_id}"));
    }

    /** action 是否存在（手环发未知 action 时回退 url 直传） */
    public static boolean has(String action) {
        return action != null && MAP.containsKey(action);
    }

    public static String method(String action) {
        ApiDef d = MAP.get(action);
        return d == null ? "GET" : d.method;
    }

    /**
     * 按 action + params 组装候选请求 URL 列表（多模板 = 依次尝试）。
     *  - 模板中的 {key} 占位符替换为 params 对应值（URL 编码）
     *  - params 中未消费的键值以 query 参数形式追加（值为简单标量）
     *  - 返回 null 表示 action 不存在
     */
    public static List<String> resolve(String action, Map<String, String> params) {
        ApiDef d = MAP.get(action);
        if (d == null) return null;
        List<String> out = new ArrayList<>();
        for (String tpl : d.urlTemplates) {
            String url = tpl;
            Map<String, String> rest = new HashMap<>();
            if (params != null) {
                for (Map.Entry<String, String> e : params.entrySet()) {
                    if (e.getValue() == null) continue;
                    if (url.contains("{" + e.getKey() + "}")) {
                        url = url.replace("{" + e.getKey() + "}", Uri.encode(e.getValue()));
                    } else {
                        rest.put(e.getKey(), e.getValue());
                    }
                }
            }
            for (Map.Entry<String, String> e : rest.entrySet()) {
                char sep = url.indexOf('?') >= 0 ? '&' : '?';
                url += sep + e.getKey() + "=" + Uri.encode(e.getValue());
            }
            out.add(url);
        }
        return out;
    }
}
