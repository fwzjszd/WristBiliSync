package moe.mcns.BiliMax

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局轻量偏好存储：主题模式与同步服务偏好。
 * 应用全局只有一个进程，直接用 SharedPreferences 即可，无需 DataStore。
 */
class AppPrefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("bilimax_prefs", Context.MODE_PRIVATE)

    /** 主题模式："system"（跟随系统，默认）/ "light"（日间）/ "dark"（夜间） */
    var themeMode: String
        get() = sp.getString(KEY_THEME, MODE_SYSTEM) ?: MODE_SYSTEM
        set(value) {
            sp.edit().putString(KEY_THEME, value).apply()
        }

    /** 是否已完成首次启动引导（欢迎与配置页） */
    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(value) {
            sp.edit().putBoolean(KEY_ONBOARDED, value).apply()
        }

    /** App 启动时自动运行转码服务（内嵌 HTTP 转码服务器，端口 6732） */
    var autoRunService: Boolean
        get() = sp.getBoolean(KEY_AUTO_RUN_SERVICE, false)
        set(value) {
            sp.edit().putBoolean(KEY_AUTO_RUN_SERVICE, value).apply()
        }

    /** 后台保活：以前台服务常驻守护转码服务器，降低进程被系统查杀概率 */
    var keepAlive: Boolean
        get() = sp.getBoolean(KEY_KEEP_ALIVE, false)
        set(value) {
            sp.edit().putBoolean(KEY_KEEP_ALIVE, value).apply()
        }

    /** 收到手表发来的转码请求时弹出系统通知 */
    var notifyTranscode: Boolean
        get() = sp.getBoolean(KEY_NOTIFY_TRANSCODE, true)
        set(value) {
            sp.edit().putBoolean(KEY_NOTIFY_TRANSCODE, value).apply()
        }

    /** 与手表建立消息通道时自动拉起手表快应用（/pages/index） */
    var autoLaunchWearApp: Boolean
        get() = sp.getBoolean(KEY_AUTO_LAUNCH_WEAR_APP, true)
        set(value) {
            sp.edit().putBoolean(KEY_AUTO_LAUNCH_WEAR_APP, value).apply()
        }

    var relayGatewayBase: String
        get() {
            val raw = (sp.getString(KEY_RELAY_GATEWAY, DEFAULT_RELAY_GATEWAY) ?: DEFAULT_RELAY_GATEWAY)
                .trim().ifBlank { DEFAULT_RELAY_GATEWAY }
            return if (raw.removePrefix("https://").removePrefix("wss://")
                    .startsWith("api-dev.b4qaq.cn")) DEFAULT_RELAY_GATEWAY else raw
        }
        set(value) {
            sp.edit().putString(KEY_RELAY_GATEWAY, value.trim()).apply()
        }

    var cloudApiKey: String
        get() = sp.getString(KEY_CLOUD_APIKEY, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_CLOUD_APIKEY, value.trim()).apply()
        }

    var authType: String
        get() = (sp.getString(KEY_AUTH_TYPE, AUTH_TYPE_FREE) ?: AUTH_TYPE_FREE)
            .let { if (it == AUTH_TYPE_NORMAL) AUTH_TYPE_NORMAL else AUTH_TYPE_FREE }
        set(value) {
            sp.edit().putString(KEY_AUTH_TYPE, if (value == AUTH_TYPE_NORMAL) AUTH_TYPE_NORMAL else AUTH_TYPE_FREE).apply()
        }

    /**
     * 手表互联 SDK 后端：[SDK_BACKEND_AUTO]（自动，默认，优先 OronBox 回退小米）/
     * [SDK_BACKEND_XIAOMI]（强制小米运动健康/小米穿戴）/
     * [SDK_BACKEND_ORONBOX]（强制 Oronbox）。
     * 必须在首次 Wearable.get*Api() 调用前通过 WearableBackendConfig 生效。
     */
    var sdkBackend: String
        get() = sp.getString(KEY_SDK_BACKEND, SDK_BACKEND_AUTO) ?: SDK_BACKEND_AUTO
        set(value) {
            sp.edit().putString(KEY_SDK_BACKEND, value).apply()
        }

    /**
     * 转码服务器监听端口，默认 [DEFAULT_SERVER_PORT]。
     * 修改后需重启转码服务器才会生效。
     */
    var serverPort: Int
        get() = sp.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT).coerceIn(1, 65535)
        set(value) {
            sp.edit().putInt(KEY_SERVER_PORT, value.coerceIn(1, 65535)).apply()
        }

    /**
     * 液态玻璃（Liquid Glass）：开启后底部胶囊栏与悬浮表面使用 backdrop 实时模糊
     * 与透镜折射效果；关闭后回退普通半透明实心底栏，降低低端机 GPU 开销。
     */
    var liquidGlass: Boolean
        get() = sp.getBoolean(KEY_LIQUID_GLASS, true)
        set(value) {
            sp.edit().putBoolean(KEY_LIQUID_GLASS, value).apply()
        }

    /**
     * 自定义背景图列表：文件存于 filesDir/backgrounds，这里只记文件名（换行分隔）。
     * 顺序即添加顺序，背景选择页按此顺序展示。
     */
    var customBackgrounds: List<String>
        get() = (sp.getString(KEY_BACKGROUNDS, "") ?: "")
            .split('\n')
            .filter { it.isNotBlank() }
        set(value) {
            sp.edit()
                .putString(KEY_BACKGROUNDS, value.filter { it.isNotBlank() }.joinToString("\n"))
                .apply()
        }

    /** 当前背景图：空串表示默认（主题灰色），否则为背景文件名 */
    var backgroundSelection: String
        get() = sp.getString(KEY_BACKGROUND_SELECTED, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_BACKGROUND_SELECTED, value).apply()
        }

    /** 背景图模糊化程度（dp，0 表示不做模糊） */
    var backgroundBlur: Float
        get() = sp.getFloat(KEY_BACKGROUND_BLUR, 0f).coerceIn(0f, 30f)
        set(value) {
            sp.edit().putFloat(KEY_BACKGROUND_BLUR, value.coerceIn(0f, 30f)).apply()
        }

    /** 液态玻璃：模糊半径（dp） */
    var glassBlur: Float
        get() = sp.getFloat(KEY_GLASS_BLUR, 8f).coerceIn(0f, 24f)
        set(value) {
            sp.edit().putFloat(KEY_GLASS_BLUR, value.coerceIn(0f, 24f)).apply()
        }

    /** 液态玻璃：折射高度（dp），决定透镜边缘的弯曲范围 */
    var glassLensHeight: Float
        get() = sp.getFloat(KEY_GLASS_LENS_HEIGHT, 18f).coerceIn(0f, 40f)
        set(value) {
            sp.edit().putFloat(KEY_GLASS_LENS_HEIGHT, value.coerceIn(0f, 40f)).apply()
        }

    /** 液态玻璃：折射强度（dp），决定边缘像素的位移量 */
    var glassLensAmount: Float
        get() = sp.getFloat(KEY_GLASS_LENS_AMOUNT, 20f).coerceIn(0f, 48f)
        set(value) {
            sp.edit().putFloat(KEY_GLASS_LENS_AMOUNT, value.coerceIn(0f, 48f)).apply()
        }

    /** 液态玻璃：表面着色浓度（0~0.2） */
    var glassTint: Float
        get() = sp.getFloat(KEY_GLASS_TINT, 0.04f).coerceIn(0f, 0.2f)
        set(value) {
            sp.edit().putFloat(KEY_GLASS_TINT, value.coerceIn(0f, 0.2f)).apply()
        }

    /** 液态玻璃：边缘色散（chromatic aberration） */
    var glassChromatic: Boolean
        get() = sp.getBoolean(KEY_GLASS_CHROMATIC, true)
        set(value) {
            sp.edit().putBoolean(KEY_GLASS_CHROMATIC, value).apply()
        }

    /** 液态玻璃：深度效果（透镜按距离产生厚度感） */
    var glassDepth: Boolean
        get() = sp.getBoolean(KEY_GLASS_DEPTH, false)
        set(value) {
            sp.edit().putBoolean(KEY_GLASS_DEPTH, value).apply()
        }

    /** 液态玻璃：鲜艳度增强 */
    var glassVibrancy: Boolean
        get() = sp.getBoolean(KEY_GLASS_VIBRANCY, true)
        set(value) {
            sp.edit().putBoolean(KEY_GLASS_VIBRANCY, value).apply()
        }

    /** 液态玻璃：顶部高光 */
    var glassHighlight: Boolean
        get() = sp.getBoolean(KEY_GLASS_HIGHLIGHT, true)
        set(value) {
            sp.edit().putBoolean(KEY_GLASS_HIGHLIGHT, value).apply()
        }

    /** 液态玻璃：内阴影 */
    var glassInnerShadow: Boolean
        get() = sp.getBoolean(KEY_GLASS_INNER_SHADOW, true)
        set(value) {
            sp.edit().putBoolean(KEY_GLASS_INNER_SHADOW, value).apply()
        }

    /** 液态玻璃：弹性按压动效 */
    var glassBouncy: Boolean
        get() = sp.getBoolean(KEY_GLASS_BOUNCY, true)
        set(value) {
            sp.edit().putBoolean(KEY_GLASS_BOUNCY, value).apply()
        }

    /** 液态玻璃生效范围：卡片类（首页卡片、设置页卡片与子页面卡片） */
    var glassScopeCard: Boolean
        get() = sp.getBoolean(KEY_GLASS_SCOPE_CARD, true)
        set(value) {
            sp.edit().putBoolean(KEY_GLASS_SCOPE_CARD, value).apply()
        }

    /** 液态玻璃生效范围：选择器类（底栏导轨、分段选择的轨道与滑块） */
    var glassScopeSelector: Boolean
        get() = sp.getBoolean(KEY_GLASS_SCOPE_SELECTOR, true)
        set(value) {
            sp.edit().putBoolean(KEY_GLASS_SCOPE_SELECTOR, value).apply()
        }

    /** 液态玻璃分组参数（thumb / track / button）：逗号分隔的 10 个字段，空串表示尚未保存过 */
    fun glassSpecOf(group: String): String = sp.getString("glass_spec_$group", "") ?: ""

    fun setGlassSpec(group: String, value: String) {
        sp.edit().putString("glass_spec_$group", value).apply()
    }

    /**
     * 转码服务器手动 IP（用于向手表汇报服务器地址），默认空串表示自动探测局域网 IP。
     * 自动探测不准（如多网卡/热点场景）时可手动指定。
     */
    var serverIpOverride: String
        get() = sp.getString(KEY_SERVER_IP, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_SERVER_IP, value.trim()).apply()
        }

    companion object {
        const val DEFAULT_SERVER_PORT = 6732

        const val DEFAULT_RELAY_GATEWAY = "https://api.b4qaq.cn"
        const val RELAY_API_PREFIX = "/api/v2/tunnel"
        const val RELAY_APPNAME = "TestApp"
        const val AUTH_TYPE_FREE = "free"
        const val AUTH_TYPE_NORMAL = "normal"

        const val MODE_SYSTEM = "system"
        const val MODE_LIGHT = "light"
        const val MODE_DARK = "dark"

        const val SDK_BACKEND_AUTO = "auto"
        const val SDK_BACKEND_XIAOMI = "xiaomi"
        const val SDK_BACKEND_ORONBOX = "oronbox"

        private const val KEY_THEME = "theme_mode"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_AUTO_RUN_SERVICE = "auto_run_service"
        private const val KEY_KEEP_ALIVE = "keep_alive"
        private const val KEY_NOTIFY_TRANSCODE = "notify_transcode"
        private const val KEY_AUTO_LAUNCH_WEAR_APP = "auto_launch_wear_app"
        private const val KEY_RELAY_GATEWAY = "relay_gateway_base"
        private const val KEY_CLOUD_APIKEY = "cloud_api_key"
        private const val KEY_AUTH_TYPE = "auth_type"
        private const val KEY_SDK_BACKEND = "sdk_backend"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_SERVER_IP = "server_ip_override"
        private const val KEY_BACKGROUNDS = "custom_backgrounds"
        private const val KEY_BACKGROUND_SELECTED = "background_selected"
        private const val KEY_BACKGROUND_BLUR = "background_blur"
        private const val KEY_LIQUID_GLASS = "liquid_glass"
        private const val KEY_GLASS_BLUR = "glass_blur"
        private const val KEY_GLASS_LENS_HEIGHT = "glass_lens_height"
        private const val KEY_GLASS_LENS_AMOUNT = "glass_lens_amount"
        private const val KEY_GLASS_TINT = "glass_tint"
        private const val KEY_GLASS_CHROMATIC = "glass_chromatic"
        private const val KEY_GLASS_DEPTH = "glass_depth"
        private const val KEY_GLASS_VIBRANCY = "glass_vibrancy"
        private const val KEY_GLASS_HIGHLIGHT = "glass_highlight"
        private const val KEY_GLASS_INNER_SHADOW = "glass_inner_shadow"
        private const val KEY_GLASS_BOUNCY = "glass_bouncy"
        private const val KEY_GLASS_SCOPE_CARD = "glass_scope_card"
        private const val KEY_GLASS_SCOPE_SELECTOR = "glass_scope_selector"

        @Volatile
        private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(context).also { instance = it }
            }
    }
}
