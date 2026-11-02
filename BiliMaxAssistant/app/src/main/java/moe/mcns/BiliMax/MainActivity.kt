package moe.mcns.BiliMax

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import moe.mcns.BiliMax.crash.CrashReporter
import moe.mcns.BiliMax.sync.ActivationManager
import moe.mcns.BiliMax.sync.BiliDownloader
import moe.mcns.BiliMax.sync.CloudRelayClient
import moe.mcns.BiliMax.sync.FilmPush
import moe.mcns.BiliMax.sync.NetInfo
import moe.mcns.BiliMax.sync.SfBridge
import moe.mcns.BiliMax.sync.SyncHolder
import moe.mcns.BiliMax.sync.HistoryStore
import moe.mcns.BiliMax.sync.SyncLog
import moe.mcns.BiliMax.sync.SyncStatus
import moe.mcns.BiliMax.sync.TranscodeServer
import moe.mcns.BiliMax.sync.WearState
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.mcns.BiliMax.sync.WearTransport
import moe.mcns.BiliMax.transcode.FilmQuality
import moe.mcns.BiliMax.transcode.Transcoder
import moe.mcns.BiliMax.transcode.TranscodeOptions
import moe.mcns.BiliMax.transcode.TranscodeResult
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import androidx.compose.foundation.BorderStroke
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// 与参考项目 ShellTheme 一致的状态配色
private val StateSuccess = Color(0xFF22C55E)
private val StateWarning = Color(0xFFF59E0B)
private val StateDanger = Color(0xFFFF5A5F)
internal val StateAccent = Color(0xFF3B82F6)
private val StatePurple = Color(0xFF8B5CF6)
private val QqBrandBlue = Color(0xFF12B7F5)
private const val QQ_GROUP_ID = "1076096725"
private const val DOC_URL = "https://www.yuque.com/xiaozhou-xf0uy/gx8p5n?#"


// ============================================================
// 镇码神兽 · DeepSeek 鲸鱼娘（请用宋体等宽字体查看）
// 供奉于应用入口之上，保佑本转码核心永无 bug、服务器永不断连、
// 胶卷帧帧对齐、音轨条条同步、BV 号个个可解析。玄学加成+++
// ============================================================
//
//
//                                                                               ?%#$#%7&%%#?
//                                                                          *&&8@$%@$@&$%&#&#$%$
//                                                                       o&%8%$#%7*~-.:~?#%#$#$%&v
//                                                                     &$B$&:                ?@B&8&
//                                                                   #8&$:                     =B%@
//                                                                 &88x                         f88+   !:
//                                                               ~@&f                       !vxf-&8x7vx!?7#x,:   ;v*fxoof
//                                                              7B#                      /#$*   7@$;     !?%o=#@#@       &#$:
//                                                             *8o                       %|              :!    ~|!          $7
//                                                             Wx               x#vvo$f7x7=          +|; !      ~     ,      fo#~
//                                                            8$              %7~    -/:   !  !;    /|- +|+   =/*    ,~      |o&*
//                                                           ?8$            -8-       ~/   *?* |*o/*=o==x=/x|7oxo; /| ,     !+-f$$! :7=
//                                                           %@%         o7?v7        /?++??x=*o&$$&@#%#&7%7&*- ;/+/=+/  /=/     v&&x,?v##
//                                                           x@8|      v%! |//|   ;/!=xvx7$W8B@WB8&@&$%$%$%$%$&8$@%7  |?xx/=     -~      ;$7
//                                                            #&@#%o| &f      ?/?+!/f&@&$%$%$%$%$$B@8@8$8$$#&#&#&#&#@&$= :?+|=+!+~         #f
//                                                              .~    &    -  =*=7v@$$%$$W8B@@&$%8&$%$&@$B8B@@%$%$%$%$&$&@#- o=+?   ,;,     @@:
//                                                                  #7~   !/+!*o@&@%&%8@B$8&$%$%$%&%$#%%&#&&@$8$&#&%&#&%&#$%$v-~v==|--     /$%@$=
//                                                               &%#%&!|!  |?f@W$$&$$W@8&@&&&$#&#$%$&@&$%$%$%$%@@8&$%$%$%&7&%$@@~~?=:;;/:;!v&8&$&@:
//                                                              @/   -+/==x?@8@#&#&@B$&#&%&#&#&7%#&#&%$%&#&#%#&#%#@&&#&#&#7f%#&#@%;|f*?/    !&@&$#8#
//                                                             $       =/ffB%@&$%$8B&$%$%$%$%$%&#$%&%$%$&$%&#$%&%$%&%8#%77f7f%#&%$$@~??|:--|-|  &8%&$|
//                                                            @~   ;/!|~f#8#&#&%&@@#&#&&&#&%&#&#&#%#&#&#&%&#&#&#&#%#%7@&7vvf%#&#&#&#8o;oo++      &&%&$*
//                                                           ;$    ;|==v&8%$%$%$@@%$%$&$%&%@%&%$%&%$%$#$%&$$#%#$%%f#%%#8@%7&%$%$%&%$%8$ o?~!~:    @@$&@!
//                                                           :$+/; .-|x$%#&v%#&&$%&#%#$#%#&#&#&#%#&77#&x7f&&%77x7xvxvf##&$&7%#&#&#&#&#&$?+?;      B%&%&$|
//                                                            !@+*+x*7@#%$7#%&%@%$%&#$&&#&%&7%#&##x77&&#x%#88@%&7&#&%&#&#$@@#$%&%$%&%$%$@&+o/?+/*B@@%@&&8;
//                                                           o%   //v$7%$##f%7@#%7&#%&&#7x%v7xfxfofx%7&#%#%#M@$#&#&#&#%#&#&$@#&#&#&%&#&%&&@xx/+/?@@%$%$&%@
//                                                           $?  //f@#%@%$%#v@$&#v#778%&f7v$7vxvfvf&&$%$%&%$@&$8&$%$%&#$%&%@@8%$%$%$%$%$&$88x+-   @@$@&8$8&  ;:
//                                                           #%!*=?@77&#&#&7#&&#7xfo%@&##7#7vv7xf7%7&%&7%#&7%&**&#%7%7%7%#%#&$@#%#&#%#&#&%$@%o*|/-B&&&$&@&B7&f8o
//                                                          v$ ;/*8#v$&$%$%&%@%%#&###W&&#$%&#&%&%$%&%@%&#$%&#@o|=x%%#&#%7&#%#@@$#&%&%$%&%$&$B8&$%W8@&@$88@xfov7B
//                                                          @+ !=&7f&#&%&#&#@%&#&#&#$8@#&#%#&7&#&#%#$7%#%7&7%7%:~/o7$##v#v#7%#&$&7&#&#&#&#&%$@7ofov&8$@@#x#ffo#@
//                                                          %#+*$&f%&@%$%$%$$$%$%&%$&B$&%&#&%&7$#&%$#%7%#%&&#&%#v7=**&#&#&%$#%#@$&#$%&%$%$%@&8%vxff&%8&#$$#%fvvB/
//                                                           &&f$f7#&&&#%#&#&#&%%#&#$7$#%#%7%7&7%7&v#v#f7v&&7v#!     :+f#v#77v%#@7%#%#%7%#&#$#87xof7##B@B@@#&#%@7
//                                                            =Wv@7$&@%&#$%&&$%$#&%$&x*@#&#%7%#&7&7#v%7#v#%$v#7=      :!+%#7#%7%%87&#&#&#$%$&$&B7vx##@BB8WBW8MB8.
//                                                            $7$%%%$%&%&#%#@#&&&7%%$+|7$7&7#v7v%7%v7f7f7f%f#o#+:      ,-,|f7f%7&$#7%7%#%#%#$%&@W#&&@%B@B$8$8&@@8#
//                                                           +@$8$&@$@%$%&%$$$#@&&#&%$#v%%78v#v#v%7&v7f7v#v=vvv+: :+**+*7=|+v8f7#$$&%%7&#&%$&@%@BW8W8W$8$8$8$@&@&@8B|
//                                                           &#&M7$%$&&#&#&#@%%#&7#%&;~,+7###f7fvx7f7vvovx#~vo#:-/?+=$B$B8W8MB8&&#@v%7#v#7&7%&&7@8B@@&@&$&@&$&$%$&$%B@8.
//                                                          *@&M8&&$&@%&%$%&&8#&&&###!:  +777%v#fvxfx777x#f~x7x~:|=$8M8W8B8MBM8MBB%%7%7%7&%&#@%&&M88$8$@&8$@&@&@&@$@$@$W8B!
//                                                          @#@@B#$#@%&#&#%#&&%7&&#v&-   ,;fvfof#oxox?vf7f/:vx~:-|#&%&8&@#&$8$W$@@B@@v77#7%#%%&%&8B@@&@&@&$&@&$&@&@&@&@$@%8@8#|
//                                                         ;@$$BB@$$$@%$%&%&%@&&#B7##;  ,;/?o7v*x77vofo#vvxv7o!~,;+*|B@8$B@8&@$M=7@87#7&7%#&#$#@%W8$%@@B$8$@$@&8$@$8$@$@$@&@%%@W8B$o
//                                                         f@$#$B$8#$%&#&#%7%#@7%&vx7|;?&*&%&##fx:oovxvx*|!*-    ,:  &$vv$B%xx%$+oo%7f%f77%7v#%&%$B#fxvv&&&#&&@&$&@&@&@&@&@%$&$#&%$%8$8&@
//                                                         $%8|8BW$&&@%&#$%%#%$&#@=xv#788M8M8B8B&x!x=+~  -:-         ~%f?*!/=x7$;!~&f%##7%77v&%W%88W77xfo+/=+?ofof?fofxvx7f7#$%&%$&$%@$B$
//                                                       *#&@v-@@8&#@%&#&#&7%7#$&%=~?@WBB&W$$&o&8$|--,:             :,;vf+++?f?!|~xv7#7v#xvf%7&B8$88B%o!++/|+|/|/|?*=|+|+/=+**#@#%@&@&=
//                                             !*o|  *x7v7,8@##8fM%$@@%$%%#&7%7@@@=!&MB8=W88$@@B$@@;,               :;~|!/|+|+|+|+7#&vf#f7f%%%@W@B@W88@87#$f/+|+/?/=/*@B%$&$@@%
//                                                  :      o878&B@$#8&&#%7#7#v#v@&??@@8+/$B%&7@$@*#|                :-;~|!/!/|/|=vv&vf7ofx777&B$@@@@@&@&@$8$@&B#$@%7$7!
//                                                          $BBB%B8$BM&$#&#%#&7#v887@B&= ~$87v?*!=v~      -         :;~|!+|+/=*v7v7&f7fvf%##&B$B@@$W@8$8$8@8$8B&
//                                                          %@W#8$B%W@8#&#%7#v#v7vBB8v&v~  /f7%?*~~:-,    ;.        :,~-!~|!/;|!|x&xvxvv%$#%B&8@87$8B$$%@&@$8&@8!
//                                                         xWB$B8B8B8B8B%$#%7#7%%#7B88//~!!+!+++||;!~~,      ~  :;-;:-:;~!;!!x*ffvxvv$$8@&$W@8&@%&&W@W$@%$$B@B$8@
//                                                        $8%&8@8@8@$888@&&v7v#v##%f%#@v!;|!|!/!|!!-~::   ~/*+*=*?ov#, .::~,-~+*x?7$&&B&$&@&$#&7%7@$8@8%%#&#@$B&@&
//                                                      -M8%%@$8@B8B8WBW@@@%7%v7v##&&B%&%o*/!+|/!/;;-~, ,&%7??=?=?=?f*    ,:,-:~|8&&%@8@@@&&7$%%#&$8@B@@&$%&%8@M@@$;
//                                                     7W%$&@&@$@$8@B$8#v#@8@7%7vxvx##$v&&|!|;|!|~;:-,: -x#??+*+*+*+x         -*8#%7%&8$@7&v&7%v%#@#B&@$@%%#&#$8&&%%v
//                                                   +@W$8$8$8$8@B$%v7v@$$&BBB@&&$v#v7f&#$$fxo!|;;-~:    -!x*?=?=?=*,       ;%&@%$%$&@$8#&v&#%v##@8&88@B$@%$%&%@B;%8%&
//                                                  8@$&@&@&@$$fvxvofxvofv%$8@B&$&8&$%%%&#&$$;~,-,-.,       -:;:-.       -7%&7%#&#&7%$@%&v#77f7v#8B78$@&$#@#%#&#&&  #%&?
//                                               -8W%@@8$8$@7vofofofovxv%8@8@8B@&@$@$@&@$$%@$B$$~-: .      :.-,:     --fxv8%#&#&%$%&%B$$v#7#v#7#@B8&@B$8$$%$&$%$#B#  -#&7
//                                             #B@@$@%&xx?o?x?xof?xx@$$$8$@$8$B8@%$%$#&%$&@%@#%%@&$#%7#7x||~-,-:-.~*fxv?o=B#&7%#$#&#&$$f7f7f7v7&8&B$8$8$@%%#$&&%&#@#   -##*
//                                           $8B$$%7oxo$8%oxo@%W$@@W@B$8@8$B8B@BB8#&f#%$#%&8@8%@%$%&#&%&%$$B8%v%%%7f?o*o?f@$&@#&&$#$@&v&v7v77%@B$88W@8@8$8%$%$$8&$%@&    |%%
//                                            $&@%%#$%$=-#$%#-   |B&@@@&@&8$8$8&@@8v7fvx7v#7&&8%$#&#%%&7%$7=#f#fxf?=*=vvo*?+f@&7&&@%#&&xffvf#$8&@$@&@&@&@$$7%#&%8&&#&$=    v#
//                                                              ~B$8BB@8$$@B$B@@88@B%$%7f#v#v#&8&@&$%8%&%B?*+?=*+of77%*?=?=*=B@@&8&88#ffv7x%@8$8@8@8&B$@@8$&#$%&&88$%$$@   !#%
//                                                             +8&$#W&@$$%@$8$@@#of!=v&#%7#77xff7%@&@$B@B$@&v==+*+%f?o7=*+=+?*%@8%@@#fxx#x%@@%@&%+*+*/8$8$@#&#%#&&@$8&%#@&%f&%#   /f7&
//                                                             8$$ B$8$$%8@B@B$8v7?v  !x7&%&#%fvv&v8%W8W8B8W8W8W$8&@%$8B=8@WBW8WBMB%vfo#7#B8v7vf==/=~ 7B$888%$#$%&&88@$8%8;;7@%f &7|/$
//                           -                               ~$#- ?@@&&#%$8&B?o=*=ox!    |?7&#%vf7vo#@B@8@B@B@B@BB$77#$$W8W@B8B@B8B%xofx7%$#7?*//     +@$&@$%&&7%7%#$$&.%$ .@$&7&?!~@      /=
//                          +                               !&f  !8@%$%&$B$8@?=*+*+*ox   ;~|+f&$v%7ffB8B@B8B8MBMBM&%7$%&8MBW8W8W8W88ovx%8@=fof?*/!   *?W$8$8$@&&#$%$%@$8|$-;&&$8;~-$-   v##o@
//                         o                               |$~  +@&#&#&&8&@$8/=+*/=+&&       |x=$7#of7W@B$8@8$88W8MB$7BBM8B@8$8$8$B@fox788?fx;- |-   ?%%B&@&@&$%$%!?$%&%Bx;~$%$:;-@? +#7!-x%:
//                        -#                              ~@v  =@&%$%&&B$8$8@$+*=foMff~/;~!?=?=xf@fvov8W8B@WBW8MBMBMo%vMWMBMBW8B@B8Bf%of7W&x??=+~    =ff&8$8$@$8&$%|!o$$@|-  ~~;-$@W&*~!?$/
//                         v                              $#: &%%#&#%&@%$$8$$fo*o7W87       =+ox7#f?#x7@B8W@8BM8B8W@*+?#W8W8WBW@B8Bov#x?f#8,*-;       fv?@@&$&$&@#$v~,f7=~~,-:-.-:+~;:vx#vxv7v@
//                         &~                            $&@%@7&%$%$$8%$$88v+?=?#MBW8-      -+:xvvv@8vv*@B$o8W8W8BB%==!=$WBW8WBB%87?=&#fovx7?=|! +    %xfW$@&8$@$8%@,-  :|/|-!~;:~:;~B@%=!;f7#
//                          &|                           %@&%7&%&%@&$#&&8&8v*+*oW8B@W@ +|?voxo7f777#W7*!   @8$8$8$B|?!/|*88@B$8@8   +?#x7#xo7fvf7*  +,B88$8&@&$%$&$@  :   :|!-!;!-::--~~7f7;
//                          :&7                         #8%&%$%@@8$@%&&8@8$W&??MBB@B8W@88f =*fx7f%*f&W*~;!&B88@B@8@-= |  xB8@8@B@B/~! +v?B@vxf*/-|;!$W8WB=@@$8&@&W$Bf~,-  ,/!!~!~;,~:+$8#@o
//                            %##x                  =o#7%#&#&$8$@%&7%#@&@&@$*&B&8@8$8@B@B@~;|!+/*vvx#8&.-%@&@&@$@$7;o:   -7$@$@%@$Bf~  :/#8of+/-     $B@B8B#$&$%Boo=&x~,, :,~,-.,.-:@$$#%7%7#-
//                              v&&&7%v=,     -=x%#&%$%&$@$W@8$@#&%&%8$@$@$B8BBB8W@8@B@BBo    ;oo%7*!B$;~$&$$B@@&B/||= ~;  $$@$8$@&$ + ;-f8?*|;!-   =M8B8W8W$$&$8 ;x$o+=!;-~:-,:.~7B$$$@%@%&v%7/
//                                :v#7%%$#%#$%&#&#@#&%&#/*8$8&&#&#%%@&$&@$B&8@8$8$8$B$@$B8|     |!o::%@!~  |==$&8&!:|+!    =@$@&f!+  ;    87x|;    ;W@8@8$8$88$@B / v*x?*+=|!-~?#$8&$#x%f!%%@#77%=
//                                      ;&%@@$%$v|     &@B&8%$%$&$&8$@&888$B@8@B@8@B8B@B8WB8 | ;;+/=~7@v   !  - ;//-:|| /7! =?~!: ;; !    &$o+|;|@$M8B@B@B@B@8BB8++  ; ~x?+*=%%f?o*W&$#o&|  -&#$#$##-
//                       -!~!-       ,#7%#$7;       ?%8&$&$%$%$#&%$#&&888@B$@$8&@$8&@$8$B8B@B88  ;//~|8#   .  ;   = --~v8&Bf=! -!  ~  !   %%x|  @BB@@$8$@$8@8B8$8&*     =?*+#xo**+*&@#@!v%     ~&###&7|
//                 ;x##%v77%#$%vv##%%@#%&$##v7v%&$$8$@$$%$%&7&#$&@%$$8WB8W8W@B@8$8@8$8@8@B@B8WB+!! ~! $@   ~  -   +  /; &@&:=;  /  |  ;   f8**+|@W88$B@8@BBWBB8B@B& !  ;: o#xo*f=?=*&8%$ @v      +$#&%%%:
//               f%fx?vovox|/;of&7#fvv%7#v7v#v##%7%%&#&#%7#7&%$%&v$&@&$BB@B8B@8@B@8@B@8$8$@$B@B   |=x $$   .  ~   =  ;|,    *|~ +  ~  !   o$x/ fW@8@B@8$B@BBM$8$8&@$& +/       |o*?=W#&& %&        #%%:f&v
//             %$vf&v         =@#$$@%#7&%&#&%&#&#%7%7%7%#%#$&&7%%@&@%%%@WB8W8W8W8W8WBW8W8B$B@W8Wfv; *-7W*  ~  ,-  +  |!/    ~+  =  |  !   oB*&@MBB8WBW8MBWBWWB$8$B@B@B$f~+|/++ ~-|#8@@&8f@%/        %$%  %7
//            77v%=          ?8#%%&#%%$7#7#v#7#v#v#v#7%#%7#v#7%%$#%7%#&7#8B@B8W@8@B@B@B$8@B$8@B@W8$~o/!&%   ~     *: ;=~    ;=! =  ~  /  ,f$o8W@B@W8B@B@WB8@B8B&8$B@B@8$8$8?++$$B$$f%#&&@#@+         %$   #v
//            %$&         x&%%f$&$%&#$%$&@%%7%7%7&#&%$%%7&#%#$%&7%#%%$#%7&@WBW8W8B8W8W8B@W8W8W8WBW@~-=;8$   ;     =  !+/!7%*~o: +  |  ;  ;f8BMBW8W8B8WBMBB@B@BBW$88W8W8W8B8B@WBWB@&#v&%$&$$;         v$x  x$
//           x$#     ovv#77f7x&&&#&#&#&#&#&%$%$&$%%7%7%#&#&%&7#7%7##&7#7&#&%M@8@B@B88$B8B8WBW@B8W@B88o+$B   |  :  =~ -+-$$8$!+  *  |  ~  !%@WBW8W@88WBB88$8@8$8@W8B@B@W@8%88B$B%&%$v7v&%##$          #%#  7%=
//           &v$ -#@%f#&v#v#vf&$%$%$%$&@$$#%7#f##%#&#%#&#%7&#%#&%&7&#%7$&&#M8B8W88@B@8@W8W8MWMBMBW8WB7!%8/  +  +  */ |+~ =/ +=~|+  = !! !/$BBM8W8MBWBB8B@B@B@8@B8W8MBW8M8W8W$%#$%$&7v7%@7v&@        /@7@ /%$
//           #vf%%vx$%7v%xvxx?$#&#$#&##v#7#v#f7v$$#v#7%v#7%v#7%##v#v#v%#%7B$8$8&@$8&8@B@8@B@M#%BB8B$B&//Bv|/%#$%@&$&@$8&@&@&@@@&@%%?;+!~! 8&W8B@B8B8@@B$@$8$@$8$@$8@B@WB8$8v#7%#%%$ovf&%vxv&7-     =$v%7v#&=
//           &77v%%$&7v&vvxfof$$%&-           7$#&#%#$#%7%7##%%#v%7#7%#%#%BB@B@B@B@8@B@B@W8M#%#8BWBW@W$@8WBB$8$8@B@8$8@8$8@8$B@8$8@B8W@B$8BBWMBW8BBB$B@8@B@8$B@8$B8B@BB%#&#%7%#$#@vxf#%$x7f7#%#  !%%v%&&7#-
//         v@v7#$, %vx7v#oxoxo@%            ?#7%%7#7%#&f7f##vv7f#v#7#v77&8B@B@B@8$8$8$8@B@W8%7#vM@B@8$8$8$B@8=*=B@8$8$@$8$8$8$8$8$8$*f8@B@88MBB88@B@8$8$8$8$8$8$8@8$88%7%&#7%7%%&#x?77&xfofx&#v%&#7f7#7=
//        &%%&#7#%&7f7vv7#ofof&=     o     #%*%&#%v##&&7v%#+7#v##&7%7#vM8B@B@B8B@B@B8B@B8W8o7@$8BB8W8B@B@B8W88f888$B@8@B@8@B@8@B@88@xv@W8B8WB%WW8B8W@8@B@B@B@8@B8B@WB$7%#@%%#%%$&vov#$vvxvf7%$#v#&v7%f
//       %#v- |7vf$xox7oxf7oxox#+   7+    7#x%#fvfvv%f$xvv!fvf7f%##v%%8B8@B@B@B@B@8$8$8$88*+**o=&8B@B@B$8$B$8$B$@$8&@$8&@$8$@$8&@$8$888$8@B8&7$8B@8$@$8$@$8$@$8$8@B$##%v%f%v#7$%%?xv&ffofxvx&&%?77&x
//      #&v    -#7v7*xv7?xf#v7fx7$- v#*  ;&&%%v7f%%7x$%v7o77f7v#$@&%8W88$B@8$B8B8W88@B@BBo=*+*=?@B8W8W88$B8B@B@B$B@8$B@8$B@8$B@8$B@8$8@B8W8M&x=*$M8B@B@8$B@8@B8B8W7%7&### *$7&&$ov#&oxovf7f7#@%ff&7%
//      $#       x%ff?x7v*o?vovf7x%v#7777#%7%7#v#ofx$vvv#fvx7xv7$#BB8@8@B$8$8$8@8$8@B$BB7+o?fofoW@B8B@B@8$B@Wx+/8@8$8$@$8$@$8$@$8$8%7%8@B@BB#+=/=+88B$8$8$8$8@B@#v#7%7#v7  %#%&o##%?x?ffvovf$=7o77#7#
//      $=        %7vvx##v*oox?xxffvxv#&v#%&vvofo7%@7%%$77f777f$BW@B@8@B@8$B@8$B@B8W8BWB=xof?oovB88MBW@B@B8W8&of@B@B@8@B@8@B@8@B@8@xof@WBB8WWoox?*+?8W8B@W8W@#v%7#7%#&7%  ~@&$f$%vofx7fvovf#$7f7&8#v%
//      &+         #v7#ov77***o*o?xoxofoff%vvf%#&#$7%##v7ovx77B8@$8$8$8&@$8&@$8&@$8$88W+=+*===$@$=?+f&@&@@W88@B@8$8$@$8&@$8&@$8$@$8$8@8@W@B@@oo=x?=/+/x&8@7v###f7v#vf##   %%%7#ox?fxf?o?vfv&%xv7-|@ofv
//      x&-      ! ~&&#%7#v#ofoxofxfofxfofofofxfxv#%v#vvv#v#8M8B@B8B@B@8$8@8$8@8$B8B8M=??xo88B@W+?==/+|~ --~;=x&&@&@$@$@$8$8$8@8&@$8%@&#/++78?=?=*+*+*=x8#v#7&##v#7#%7  f$$#x7xf?o*o*xo7f7vfo%x   &x?&
//       !#7v, |vf   7&#%7%&#fvof?xox?xovoff#f7v&##7vov$B@8@B@B88$8$8$@$8$@$8$8$8$B8W*xof@W8?+==f==/+!/!!   ::-:                    -::,;/=+W&$$f==+*+v@7f7v7#%#%7%%&7#77fvfvoxof??*ox7xvf%7o     %?x7
//          !vfv?     ~$#%#@%%7%77xvf7fvxvf%7&7%v%7#&8@B$$&B@8$8@B@B@8@B@8$B@B@W8BWB=?%MWM$?+=*x%@?o?+-       :;-~,                      +=%&?=ox@?o*?$#7##&v#v$%#7%7#v#v7fvxfofxff7f7x#&x        #v7f
//                      %77v&   ?v#xf#%vv7$+-&vxvf@@B@B&$&8$8&$&@%@@8$8$@$8$8@B@WBo+f@B@B@    +v*| , /&xo=?=|                         !f*=f7*#7?+|8v*+@f#7$xvxvov#%7vf7xvf7fvxf?xx7v#7%v          %x&
//                       &xf%v   vv o%fvx#f   %#f%BW7vx%$B@@&$$@$8@$@W@8@B8B@B8M&W$WBWBW8Mf/=?v*/ -|  :f+=!~:!!++*+=!=+=!|;!|+=oo*!o=vff !-//*vv==fM8@@7x%fvf7fvv%%%#%7%7%7#v%7#7#v7v%*          o&&
//             ~f        x#?#+vfo+%o%ofx7*  7fv7#;      |!|x@$$$8$8$$&B@B@B@B88fB8W8B@B8Wv+|/xo|! :~!   =+/!/:       ,:      :;,   ;*+  |!~!+,=*v/=$W88f7xvxvf7vfx7v#v#v#7#v7xvf7f7%o          ~#%#
//             f7        #ovv  fv7f#7&v+    8*?xxf?    !~  -~?@B@B@88@8M8W8M8f@M8W8WBMf+==+oxf//   =+/    |=|+!                 ,~!/   !+/ //~ =*7?*+*$W$@f7#7|$v7xvfvf%xfofo7ov%$v&%       /%#@%|
//              vvv*  !o#v7o        ofvxvf7%#??+==x.ox     :;|+&8$8$88@8BBWv8BB$B@B@B7~    /x~/-   ~+|      |!|;~           ,.~:;;     |/  !+-  |*f!/|+%8+$%o %xvxfo%v%fo?x?ff#! x%ov7#o7v7vv7%*
//               !xvv%7%fo              :  ;%o=**%&%oxo=      :*8B8B@W@M8x&M8W8B8W8W8    +/7 !~;    !+!        ~:;       ;~;-;~;      ~|  ://!   /o7=/:~BM???$$vfv##- 7fofx7#=     #v#777&7%7!
//                        xx/          +7x7v$7fx/|=*?+fo     |*+v8B@B@B$78W@W@B8B@B88~   ,?/! ~:     ;/,                ;-~-~        ;;   ~;=.    +*f;|/B8W&?++f&&|  +%ff?x&;
//                        #v=#v#77v%#&%?!;-~      ,~/*=#x7? ;| =?W8WBB7MWB8WBW8W8B8MB#!/+=?;~ :-      !+                            :    ;~!/      |ox=?BW8M8*+?| v&v~#&ofx$
//                         ;#xo,-,~,;-~.-:-        .,/?=?vf   =+$8W@x8W@B@B@B@8$@@B@@~; ;? -           ~:       ,:~                     ~-;-|       ;x/ +$W@88o+*|  -v&#v?f7      |                                          |f
//                      !%o7ovv7ox?ooo;~:~:          ;x##v* /?=%@B%BBW8B8W8B@B@888 ||-;=*x--                     ~-~,                  -~!~!;       -;x*/+= &WB8??+=+*-+v@vvffxvf7~                                         7M@W!
//                      ;v#fvxvf&fvooovf#xf|=!:    .~x%+**oo*|+%;@M@B@B@B@8$8$@@8      ~* !                         - -:-              :~:--         -o=     #W8B/=+*+=/=/#v#7#+                                            W$@&M:
//                            v&oo7&7?7%77o@$|,    !#$=?*o*xx?&BWW8W8W8W88@B@B8M+    |!++!~                           ,~,             :;:~:~          ,v|+!   8W@W=*=*+*+*+*+=/7vvv                                        fW@$8@W
//                           %xof%%xv?    x7|: .|vvv#x7f#v##&@W@8@B@8@B$@$8&@@B8     .!+ ~                                             .,.-.          --x!~   %@8$W==+=++/+|+/+|/!;?v?v                                    8$&@&$8$
//                               -;      $+|-+v%*         *&BM8B8W8B8B@8$8@8@WBW&?; |=+f |                                                             ;?o/~~o@W8W$M7*=?+ -+/=/+|=/+-  %7x                                88&@$@&@B
//                                      vv=?%x        ~f7vWBB@B@B@B@8$@$8$@$W8B8Wo! -!?+-:                                                             - ?=|!|B@B@B&W$=+*   //+|+|+|+!!  /@+                             @B&@&$&@&8%
//                                      |&7+       ?v#7x$M8W8W8W8W@B@8@B@8@WBB@W8     * ;                                                              ~;x;  /fMBW8W$M8?=/   //=/+/=/+/=o%7                             8B$8$@&8$8%W
//                                             ~vvv=**$88@B@B@B@8@8&@$8&@$B@8@@&=-  !;f ;                                                               ~ x!/;/+ &B@8$88?/    !|/|+|/=7f;                             *@@&$%$&@%$&$@
//                                          o#7f=?=**W88@W8B8W8B@B@8$B@8$W88Bx      ;=? ;                                                               , o!      BW@W88B#:    ||=v%7=                              -BB@&@&@&@$@&@&W~
//                                       ~%7?/=+*+*&W&8@B@B@B$8@B$8$8$@$B@8$B      :!;f !                                                                ~/?+!~   8@B@B@@8$     fv-                                @M&$&@%$&@&$&@&$8=
//                                     7%f+*=*+*=#BW$B8W8B8B@B8W@8@B@8@W8B@B8     ;|!+x |                    '                                           + x;     $W8B8W8BBW  f%!                                B88$@&@$@&@$@$8$@&M/
//                                   v@|++*+=+**B8$&8@B@8$@&@@B$@$8&@$8@@$8$B         * !                       '                                        ; o/!  ;|B@8@B@8@8&B7                                 %W&$%$&$%$&$%$&$%$&@8
//                                    f#7o=*+?8M&B@B@W8B@8@8@B@8$8@8$B8B$8@BBv      :=x ~                 '                     WWWW                     + o|=/|!B8B@88B@W8B@W                                BW&@&@&@$@&@&@&@&@&@$W
//                                       +7?&88&B@8@B@8$8$@$B$8$8$@$B@8$8$@@B8=?/.:|/!x! :                  '                 WWWWWWW                    ! *!      @8$8@B@8@B$W@                            |8@&@%$&@%$&@%$&@%$&@%$8
//                                        -MB@$W8B8W88@B@8$B88$B@8$W88@B@8@W8W8W      ;v |               '     '              WWWWWWW                    + f;!,    W88$B8B8W8B$MB8,                        oW8$8$@&@$@&@$@&8$@&@$@B$
//                                       7M%&$B@8@B$@$8&@$8$@$8&@$8@MCNS@$8$B@B8;     *x  -.               '  '                                          ! =-,~    @8$@&@@B$8@B&W@B$#                     :B@&$%$&$%$&$%$&$%$&$%$&W
//                                      @M&$@W8B@B@8$8@8$B@8$B@8$B88$8@8$B@88W8M&   :?|=o !-                                                            : |o+     =W@B@8$B8B@W8B&MB@$W@@/                 8B&@$@&@&@&@$@&@&@&@&@&W$
//                                    78B7$@B@8@8$@$8$@$8@8$8$@$B@8$8$@$8$@$B@8$$-!.    *= ;               WWWWWWWWWWW                   v              | o!=++~|&B8B$@$8$8@8$8@B&BB@&@&8$@$@$$&@$$&#~   ,W$@%$&@%$&@%$&@%$&@%$&88
//                                  -WB@&@8W8B8B@8@B@8$B8B@B@8@WBB@B@8@B@8@B8M*       =*/v+ /             W  WWWWWWWWWWWWWWWWW          -f~        %   ! ;=|    !@$W88$B@8@B@8@B8B&WW8$8$8$8@8$8@8$8@W8W@fBB$@&@$@&@$@&@$@&@$@&8W
//                                 fW&&%$&8@8@8&@$8&@$8$8@8$@$8@8$8$@$8$@$8&B88      .=~|;o; ! .         WW  WWWWWWWWWWWWWWWW            ;-.   v@%@&-  ! ?|-:     ?88$8&@$8$8$8$8@8%@B@&@&@&@$@&@$@&@$@&@8W&$&$%$&$%$&$%$&$%$&@8:
//                               ~8B&&&@&B8B@8@8$8@8$8@B@B88$B8B@B@8$B@8$8@8BWB&          |x| !;        WWWWWWWWWWWWWWWWWWW            8$8$$+   :$#   ! =!-~-~    &W8B@8$8@8@B@8$B8B&@W8$8$8$8$8$8$8$8$8$8$8&@$@&@&@&@&@&@&@&W8
//                              %B%%$$8@B@B$8$@$8$&f8$8@B$8$8@8@8$@$8$@$8$@8B@B88~       /?|*? ~|,      WWWWWWWWWWWWWWWWW             @&@&@$8%&#$$+  ; ?f+       ~W@B$@$8$@$8$@$8$8@8#&B8&@&@&@&@&@$@&@$@&@&@&$&@%$&@%$&@%$@W?
//                             WB@&%@W@8@B@8&8$B@8&8@B@B8B8B8B@B@8@B@8@B@88W8B8WBW       o|+/*x- /:      WW       WWWWWW             8$  8$8$8$8@&  ! ?o/==      BBW8B@B@8@B@B@B@8$B88%8M8$8$@$8$@$8$@$8$@$8$@&8$@&@$@&@$8BB
//                            W$$%@$$@8$$$$#$$@f&7&&@$8$8@@$8@8$@$B$@$8&@$B$8$8$8@Bo +~,      **? |;      W       WWWWW              $@$@$@&$&$#+ -- =|.  !, |+7B8B88@8&@$8&@$8$@$8&8@8#$B8&@&@&$&@%$&$%$&@%$&@&$&$%$&$&B&
//                           M$@&B8B@B&B$B$8&8$$&@%@$B$B88v&#@BB@W88$8@8@M8B$8@8@W8W@BB$     *=/=f* ;       WWWWWWWWW                  &&&&@&$!::- ?x -~      =B8W@B8W@8$B@8@B@8$B$&&B$8%@W8$8$@&@$@&@$@&@$@&8$8$@&@$8BB-
//                     v%v#7#@@&B@8@B@8&#&$$8&@%@&@@8%88B7&$B@@@B88@B$88W8B$8$@$8@B8B@B8@ :!!/:   ~/f                                    |+~  |; ;?/= --     *oBB$@@W@8$8$@$8$8$8$@$&$8$@#&BW@B$@%$&@%$&@%$&@&$$8%$$B8o
//                   @7*/=+??xBB8B8W8B@8$&&8&8@B@8@B@88W$@%@@B87&W@8$$+#$M@B@8$B@8@W8W8W@x!      :=|/                                      !-~ ?*x=?+/     x77BW88$BBB8B@8@B@B@B@8$@%8@B88#&Wv 7@@@W8B8B$8&@@@&8@B@BB
//                   o$+=+=+=+f@B@B@8@8&@$@#x7$&@7@&@$B@B8W8B8M8B$8&$%$$W@8&@$&&@$@&8@B@/        !/~/                                       *+?!;   =/~   $8B8B$@$@&@@B@@$8&@$8$@$$&@$8$@&&%$Bx       !7@@W$8&$%@$B8
//                    $*+*+*=7xv@M8B@B@8$8$8$@%%&@$8@B$BBM@@@MWW8MB8%v@M88$8$8$8$8$8$B8B88       ;                                       f?o*         |=#W8B8W@8$8@8$W8B@B$%$B@8@B$8$8$@&B88&@B#          *M88&@&8BW
//                     $*+vox/=+o?B8B$@$8&@$@&@$@%%%8$B88#@@W@W$BBBBM@W@B$$%$#&&8&@$@@B@B@Bv          /+|     =*?=+=+             |**++||/=!o          ;B8B@B$8$8$@$B8BBB$8&@&8@$&$$8%$78@B@8%$B!          B8&$&@&@B
//                     #$f+=/=+o?o*#8W$8@8$8@8$B@8@B@M8@$8BM8W8@@W8WBW8B@8$8$$&8@8$B@B8W8B8W@@      :;        |*| ~+ o*?*=+x*oo+|++! /;    ++;        oBBW8B@8@B8B887v&MBB$8@8@8@@&8$&&B@B8W88B#o%#v*     !M$@&@$@&M
//                    $*=/=/+/=/=+*??*B8@$@&@$@&@$8$88W$88W$888#@@B@B$8$@$@&@$8%@$8&$$@$8$8$8@B$&  -x*8+      |  ,-   ?!   :+=*   -:-~       |~     f$W@B8W@@@B@8@@$8$$$W@8&@$@#@#%v$$8&@$8$88#=*/!,@/    W$$&$%$&$B/
//                     8**+=+*+=+?xx?f**vB88$8@8$B@B@MBWBW@88W@%@WBB@B8B%B@8$8@$#$%%$B@B@8$8@W8B8W88@M8W@8            /~    ;*      :-      78x~~8@W8W8B8$?x&@@Bf&BB&%7@8B$@$B$8#@$8$8@8$B@BB7=*=*+|$    @W8&@$@&@&W=
//                      @f=/=/=+ooo/=/=+==o%8&8$8$8@W$8@B$8$W$B$W8W@8$%%$%@$87$&@%$&7$8@8$@$8$8@B@B@B@B@B@B*;                ~            |@B@B@B@B8B$8@W@$78&@@MBBBM8B8W$B@@%$&8$@$8$@$8$B$x*x+=/=&v   &8@&@%$&$%$Bo
//                       %$?+=xf+=/*+=/=+o=?=*+@@B8M8B$B@8@B8W@BWW8W8B@8%#&8$x%@$8&@@8@B@8$$@8$8@B8W8B8W8W8W$-        /@$&%@%+         -#88W8W8W8B8B$8$B8M$%f8BWWMB8BMBWBB@B@B$B@8$B@8$BBB*+;/+oo?%&   8B@$8$@&@$@%M+
//                         v#+#f+;!    !*+=|/;//x=*??$8$@$8&@$88B@B@8$@&@&$x$&7&@$8&$&$$8@@&@$@&@$@&@&@$8@B@88W$@&&f##B@B@B@Bo|~!|7&@@W8B@B@8@B@8&@#@$@&@@W@BBW&B8W$&$W@@8B@8$8&@$8&@$8$*=?;     *8; :B@$&@&$&$%$&#8~
//                           @%%%7     /+=-   ~o=?o?+/|*$B8W$8@M8W8B$8$8$8$@&%x$&8$8$8@8@B$@$8$8$8$%$8$8$W8B8W8B@W8B@W8B@W8B@W8W8W8B@W@B@B@8$B@8$8&@$@$@$B8W@M8%@M8W$&$88W8B8B$8$8@88B?//=/=       B&M$@$8$@&@&@&@vM
//                               v&,   .      ~?x=~  ,+/=!+?&%8xB8B@8&@$8&@$8&@%&v$%@&@@8$8$8&@$@&@%@&$&@&@@B@B@B@8@8@B@B@B@B@8@B@8@8&7&8$@$8$8$8%@$$&@$@$$$B@@#B888W&%$W8B@B@@$8@8$??+   !;      %B@&@&@&$&$%$&$7$@
//                                 8&         +?      ||  |f=fo*=f@W@8$8@8$8@8$8$8&@78$8@8$8@8&8$8$$%@%@$@$B@B@B@B%8@B8W8W8W8B@B@8$8@8$@$@&B@B@@#%$@$8$B@$$B8B$$8W$BBWBM@BBW8W8B#=/o?o=-         #@8$@$8$@&@$@&@&vBx
//                                  ;&&~     ==           x=x//  |+/@$8&@$@&@&@&@&@&@&@%$&@&@&$#$%@&$&8&@$$&@&8$@$8&@$8@8@B$8&@&@%@&@&$%#%@&@$#&%#@&$&@&$&$#@@W@B8@&888$@$W@@==*+/+/+*x;       /B&@&$&@&$&$%$%$%7xW
//                                     v7&vxf&*?+?       /?o~    +/+;|=@$B$8$8$8$8$8$8@8@B$@%#%@$8$@$8@8&@&$&@&8$8@8$B@8@B$$&8$@&@$@$@$8$@@8&@##%8$$&@7&%@$8$B8W@W@8$8$8$B8oxv+o/=/+/=**     ~$B&8$@&8$$&@&@&@&%fWo
//                                         ,::$7*+*%f  !=o;      ~ .  !*x+&$8@@$@&@$@&@$8$8$8&$%&7$&@7&&@&@%&#@$8$@$8$$$@&$&$%@$@&@$@&@$@&@&@%7&@7%%$&@$@&@$@&888&@$8$8$$+/ ++f*+|+|=/+$*  f8@@$@&@&@%$&$%$&$%%x%@
//                                             !#%$?!77%x~       -,   -x=?+*+o&B8B$8$8$B@B@B@B@8@8$@DeepSeek&MingchengNetworkStudioB@8$8$@&$%@$8@8$8@8$8@8$8@88W888B@%/+/*!   -v*=/=+8@B8B@B@8$8$@$@&@&@$@&@&&f7B
//                                                    &#              ~*=| ~+|x==+%%$$8&@$8$@$8&@&@&@$@&$&$%$%$%$&@%$&$&&mcns%$#&%$%$%@%$&@&@$@&@&@&@&@&@$@&@@8%#f+==/~~|||     *7@&8$@&@$8&@&@&$%$%$&$%$%$#%xv@?
//                                                    o$v             ~?   |+f=+! |+/=+%&88B@B@8$8$8$8$8$8$8$8$8$B@8$8@8$8$8$8$8$8$@$8$8$8@8$8$@$8$@$8$8@W@@f?+//oo?=/   -       v88$8@8$8$@&@&$&@&@&@&@&$%7xvB#
//                                                      !#%v          ;-   !*+    |!/:;|+o=*?#$&8@8$@&@$@&@$@&@$8$@$8$@$@&@$@&@$@&@$@&@$8$@$@&@$@&8$8$@#=|/|+/|   !?=!          !&B$@&@$@&$%$%$&$%$&$%$%&7fxv@%
//                                                         !#%##     ;v%~  *+     :    ;|f=?/=/+?*xf%@&8$@@B$B$8@8$8@8$8$8$8$8$8$8$8$8$8@8@B@8$8$#+?f*?*/; ~!=      */        fB88$8$@&@$@&@$@&@&@&@&$%&vvf78@
//                                                             -vv7x7v* xv$+            !o=|/|+!??+//;/!/|+!+o?*f7&%&%$%&%$%&%$%$&@%&%&fv=*|+|/;!  ;=o?!!    .      -?:   ~7$$@&@&@&$&$%$%$%$%$%&%$#%vvofxv@v
//                                                                        $+            ;o/+|=/=o=;   ~+|/  /f?*/=+xo*/||=/=/++??x+=+=?o*=;  /~!     o*+;         :@88%@$B@@$8$@&@&$&@&$&@&$&@&$%$#vxvxvx88
//                                                                         x$v#:        |%%/+|//?            /f|+|+??!   !+!   |f=+/+/=o+,    -       ?/|       -&B&@&$&@&@&@%$%$%&%$%&%$%&%$#&#7xfxvxfvBf
//                                                                             v77x%x%&%7%*v#7v@             |==/+|x~           o=/=/=/?!             =8%|!*7##8$@&8$@&@$@&@&$&@&$&@&$%$%&#&vvf7fvx7f%@8
//                                                                                    W+*=*+*+?&#            .f?*+=|            */+|++*!:            .&&vv7ofxfoff##&#&#&#&#&#&#%#&#%77xfxfofxfofxv%8x
//                                                                                   $&?=?=?=?=*=%%%7##+-   ,/@ ;f@             !B#%7%f@       ;*&%&%@fvxvfvxvxvxvfvxvfvxvfvx7fvxvfvxvfvxvfvxvfvx8@=
//                                                                                   $?=*+*=*+*+*+*=?+**f7B%7=    of#x#x&f#ffvf77f=+*+=?7xvfx8&v7ofxvxfxvofxvofxvofxvofxvofxvxfxvofxvofxvofxvf@&$-
//                                                                                  &&=?=?=?=?=?=*=?*?=o*xB             Bx+?*?=o=*+?=*=?=*=?=B77fvxvfvx7fvf7fvx7fvx7fvx7fvf7fvxvfvxvfvx7f7@B@&
//                                                                                  8+*+=+*+*+*+*+*+=+*+*@?             %$*+*=*+*+*+=+*+=+*+*vW&8&7xfxvofxvofxfofxfofxvxfxvxfxfofxfof#8&$%-
//                                                                                 $%?++~- :/*=*+?=*+?=*?W              -B=*=?=*+?=*+?=*+*+*=?B  *$8$@@87#fvxvf7x7fvxvfvf7fvx7#8$8$@&o
//                                                                                 @=-:       |/=/=+*+=+$7               $*=*+*+*+=+=/+!~.:~=/B/       .+f$#@##&@#&#&#$$&#$7$x|
//                                                                                |W:            ~!+||;*8                B=!/=+*+=//,         $8
//                                                                                7$                  :+8                &=                   &$
//                                                                                %8                  :&7                8=                   %B
//                                                                                #@                  :$                 $?                   $7
//                                                                                =W                  !B                 8x                   @!
//                                                                                 @                  v%                 &7                  -8
//                                                                                 8?                 @+                 @#                  @o
//                                                                                 =@                 8                  7%                 ;$
//                                                                                  @;,              =$                  %%                ~$=
//                                                                                  v#               v&                  7$                =$
//                                                                                   @,              Bo                  &&                @
//                                                                                  ?&|             :$%-                 %&               f%
//                                                                                 $fx%|            !8?8/              #7@v              !@
//                                                                                  $!v?v*        +v7x?&               &*#f              &@f
//                                                                                 =7%=| !o*=xox?v?:+?7&;              $*/v7*          !#@o@
//                                                                                &?| ++~|   ,|.~:-|*==/v#           #%+*+~ -=o?+!=oo*?*=+$-
//                                                                                #$  +!   |!  / !  ;*+=*8          $*,  =+=;!       ;;o*v7#
//                                                                                 ,77=    .         ~&ff            %; ~~  ~~ !/~!- ~/+ |=#-
//                                                                                    #8*f*?| ,?| +??8=               x&:      !  :~  ~|  ,@~
//                                                                                    7%=/=/=**/=/=+*&                 *8*?:  ?=       #vx#
//                                                                                   |W;/! ||+/=/+!!!#&                @#+/*xo=o**=xox8~
//                                                                                   @Wv         ,!: ~B               ?%! ~!+/=|=/+|=f8
//                                                                                  +MBM| ,!-     :~ 7$               $|        .--!;!@
//                                                                                  B@B8/:           :8               8| :       ,~  +B
//                                                                                  BB8M=            :@|             +$~~       -; -/MBv
//                                                                                  W@8$@:* +%$&&&8&@&@$             &             !?BB@
//                                                                                  8M8B8@#W@B@8$@88@B8W$           =8%@$8%%f      |@W8W
//                                                                                  B8Wo$$&$B$8$$7$$8@B@W          xB@8@8$#&8$@& : oW@BB!
//                                                                                  8WWv&$fB88+;     /+BW%         W@B8W88%B8B@W8@#W8B8M
//                                                                                  *BM$&8Wv|           W#        W88%*!/|x#@@B$#&$7@@W8
//                                                                                   8WWBW88            8M~      -W$         +oBvB&%@MB8
//                                                                                    BBB$8@8x        ;&W@B      @B            7B8&@B8W=
//                                                                                    8M8B@B@B8B@8$@@B8B8B$8   +BB8&          -B8BBWWW8
//                                                                                    @BB@8$@$8$8$8$8@8$8@@@/ &@@@8$8$@7#xx#$$B@@$BBM@
//                                                                                    *MBW@8@8$8$B8B@B@B@8@88%B$@B@B@B@8@B@B@B@8@B8M|
//                                                                                     @W8W@8$8$8$@&8$8$8$8@WB$$8$8@8$8$8$8$8$8@8@W8
//                                                                                      @W8MBB@B8B@B@B@B8B8M#M$B@B@B@@&8@8@B@B@B8MB$
//                                                                                       oB@88W8B8B$8@B@B8WB|W8$8$8$8$8$8@8$8$B@W8B
//                                                                                         o@WBB@B@8@B@88M8& M8MBW8B@B@B@B8B8MBW8B
//                                                                                             ?@&@$@&$&v.   /BBW$B8B@B8B8B8B8W$x
//                                                                                                             ~@B8WBB@WBB8B$x
//
//
//
// ============================================================
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(this)
        enableEdgeToEdge()
        SyncHolder.ensureStarted()
        // 转码 HTTP 服务器（端口 6732）由 SyncHolder 按设置偏好启停；
        // 手表经 XMS 消息通道（WearTransport）上的标准 SimpleFetch 协议（SfBridge）
        // 由本机代发 HTTP 请求，回环直达转码服务器。
        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            // 主题模式持久化在 AppPrefs，切换时重建 ThemeController 以整套换色
            var themeMode by remember { mutableStateOf(AppPrefs.get(context).themeMode) }
            val controller = remember(themeMode) {
                ThemeController(
                    when (themeMode) {
                        AppPrefs.MODE_LIGHT -> ColorSchemeMode.Light
                        AppPrefs.MODE_DARK -> ColorSchemeMode.Dark
                        else -> ColorSchemeMode.System
                    }
                )
            }
            MiuixTheme(controller = controller) {
                // 首次启动分流：未完成引导时先展示欢迎与配置页，完成后写入偏好再进主界面
                var onboarded by remember { mutableStateOf(AppPrefs.get(context).onboarded) }
                // 仅冷启动播放启动页；旋转屏幕等重建不再重复播放
                var splashVisible by remember { mutableStateOf(savedInstanceState == null) }
                Box(Modifier.fillMaxSize()) {
                    if (!onboarded) {
                        OnboardingScreen(
                            themeMode = themeMode,
                            onThemeModeChange = { themeMode = it },
                            onFinished = { onboarded = true }
                        )
                    } else {
                        AppRoot(
                            themeMode = themeMode,
                            onThemeModeChange = { themeMode = it },
                            onReplayOnboarding = {
                                AppPrefs.get(context).onboarded = false
                                onboarded = false
                            }
                        )
                    }
                    if (splashVisible) {
                        BiliMaxSplash(onFinished = { splashVisible = false })
                    }
                }
            }
        }
    }
}

// ============================================================
// 启动页
// ============================================================

private const val SPLASH_WORD = "BilimaxSync"
private val SplashBackground = Color(0xFF0B0F14)
private val SplashGlow = Color(0xFFFFB067)
private val SplashBlue = Color(0xFF4C8DFF)

// "Bili" 与 "Sync" 用品牌蓝，中间的 "max" 保持白色
private fun splashLetterColor(index: Int): Color =
    if (index < 4 || index >= SPLASH_WORD.length - 4) SplashBlue else Color.White

@Composable
private fun BiliMaxSplash(onFinished: () -> Unit) {
    val letters = remember { List(SPLASH_WORD.length) { Animatable(0f) } }
    val spread = remember { Animatable(1f) }
    val textScale = remember { Animatable(1f) }
    val textAlpha = remember { Animatable(1f) }
    val reveal = remember { Animatable(0f) }
    val glowRadius = with(LocalDensity.current) { 260.dp.toPx() }

    LaunchedEffect(Unit) {
        // 系统关闭动画时（时长缩放为 0 或无障碍设置）直接跳过，避免用户空等
        if (!ValueAnimator.areAnimatorsEnabled()) {
            onFinished()
            return@LaunchedEffect
        }
        delay(120)
        // 逐字母错峰浮现，同时字距由宽收窄
        letters.forEachIndexed { index, letter ->
            launch {
                delay(index * 50L)
                letter.animateTo(1f, tween(durationMillis = 480, easing = FastOutSlowInEasing))
            }
        }
        spread.animateTo(0f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
        // 字标定格
        delay(180)
        // 字标向内收缩淡出，同时深色遮罩从中心裂开露出首页
        launch { textScale.animateTo(0.86f, tween(durationMillis = 300, easing = FastOutLinearInEasing)) }
        launch { reveal.animateTo(1f, tween(durationMillis = 850, easing = FastOutSlowInEasing)) }
        delay(100)
        textAlpha.animateTo(0f, tween(durationMillis = 240, easing = FastOutLinearInEasing))
        delay(220)
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
    ) {
        // 背景光晕：随字标一起出现、一起隐去
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(SplashGlow.copy(alpha = 0.18f), Color.Transparent),
                        radius = glowRadius
                    )
                )
                .graphicsLayer {
                    // 在绘制阶段求平均，避免组合阶段逐帧重组，也不产生临时 List
                    var sum = 0f
                    letters.forEach { sum += it.value }
                    alpha = sum / letters.size * textAlpha.value
                }
        )
        // 深色遮罩：洞口展开时把首页让出来
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    val progress = reveal.value
                    if (progress <= 0f) {
                        drawContent()
                    } else if (progress < 1f) {
                        val maxRadius = hypot(size.width, size.height) / 2f
                        val radius = maxRadius * progress
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val hole = splashBlob(center, radius)
                        clipPath(hole, ClipOp.Difference) {
                            this@drawWithContent.drawContent()
                        }
                        // 洞口边缘的暖色光晕
                        val glowEdge = radius + 90.dp.toPx()
                        drawCircle(
                            brush = Brush.radialGradient(
                                *arrayOf(
                                    0f to Color.Transparent,
                                    (radius / glowEdge).coerceIn(0f, 0.98f) to
                                        SplashGlow.copy(alpha = 0.5f * (1f - progress)),
                                    1f to Color.Transparent
                                ),
                                center = center,
                                radius = glowEdge
                            ),
                            radius = glowEdge,
                            center = center
                        )
                    }
                }
                .background(SplashBackground)
        )
        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = textAlpha.value },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SPLASH_WORD.forEachIndexed { index, char ->
                BasicText(
                    text = char.toString(),
                    style = TextStyle(
                        color = splashLetterColor(index),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.graphicsLayer {
                        // 动画值在绘制阶段读取：不触发重组，也不逐帧重建 RenderEffect
                        val t = letters[index].value
                        alpha = t
                        translationY = (1f - t) * 18.dp.toPx()
                        translationX =
                            (index - (SPLASH_WORD.length - 1) / 2f) * spread.value * 8.dp.toPx()
                        val s = (0.92f + 0.08f * t) * textScale.value
                        scaleX = s
                        scaleY = s
                    }
                )
            }
        }
    }
}

// 中心洞口的不规则圆形路径（三瓣轻微起伏，模拟参考视频的撕裂边缘）
private fun splashBlob(center: Offset, radius: Float): Path {
    val path = Path()
    if (radius <= 0f) return path
    val segments = 60
    for (i in 0 until segments) {
        val angle = i / segments.toFloat() * 2f * PI.toFloat()
        val wobble = 1f + 0.045f * sin(angle * 3f)
        val r = radius * wobble
        val x = center.x + r * cos(angle)
        val y = center.y + r * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

// 拉起「不受电池优化限制」设置页。即使已授权也照常打开，方便用户查看或改回，
// 避免点击后毫无反馈。部分 ROM 会拦截其中某一级，因此逐级回退：
// 厂商省电策略页 → 标准请求页 → 应用详情页 → 系统电池优化列表，
// 保证点击后一定有可见界面；全部失败才 Toast 提示。
private fun openBatteryOptimizationSettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val miui = Intent().apply {
        setClassName(
            "com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
        )
        putExtra("package_name", context.packageName)
        putExtra("package_label", "BiliMax 助手")
    }
    val request = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(packageUri)
    val list = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val details = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(packageUri)
    val manufacturer = Build.MANUFACTURER.lowercase()
    val chain =
        if ("xiaomi" in manufacturer || "redmi" in manufacturer || "poco" in manufacturer) {
            listOf(miui, request, details, list)
        } else {
            listOf(request, details, list)
        }
    for (intent in chain) {
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    Toast.makeText(context, "请在系统设置中允许 BiliMax 后台运行", Toast.LENGTH_LONG).show()
}

// ============================================================
// 首次启动引导（欢迎与配置）
// ============================================================

private const val ONBOARD_PAGES = 5

// 协议页配色，与手表端保持一致
private val AgreementBlue = Color(0xFF008CFF)
private val AgreementRed = Color(0xFFFF3333)

@Composable
private fun OnboardingScreen(
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val prefs = AppPrefs.get(context)
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0) { ONBOARD_PAGES }
    // 引导页自己也要有背景层，否则液态玻璃组件没有内容可采样
    val backdrop = rememberLayerBackdrop()

    var theme by remember { mutableStateOf(themeMode) }
    var liquidGlass by remember { mutableStateOf(prefs.liquidGlass) }
    var autoRunService by remember { mutableStateOf(prefs.autoRunService) }
    var keepAlive by remember { mutableStateOf(prefs.keepAlive) }
    var notifGranted by remember {
        mutableStateOf(hasPostNotificationPermission(context))
    }
    // 协议页倒计时：与手表端一致，需等待 10 秒才能点击确认
    var agreementRemaining by remember { mutableStateOf(10) }
    LaunchedEffect(Unit) {
        while (agreementRemaining > 0) {
            delay(1000L)
            agreementRemaining -= 1
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    fun applyTheme(value: String) {
        theme = value
        prefs.themeMode = value
        onThemeModeChange(value)
    }

    fun finish() {
        prefs.onboarded = true
        onFinished()
    }

    // 与主界面保持一致：液态玻璃必须配夜间主题
    LaunchedEffect(Unit) {
        if (liquidGlass && theme != AppPrefs.MODE_DARK) applyTheme(AppPrefs.MODE_DARK)
    }

    // 点击「开始使用」后整页向中心收缩成小圆，再继续收缩直至消失
    val exitScale = remember { Animatable(1f) }
    var exiting by remember { mutableStateOf(false) }

    fun playExitAndFinish() {
        if (exiting) return
        exiting = true
        // 系统关闭动画时直接进入首页
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finish()
            return
        }
        scope.launch {
            // 单条 spring：全程速度连续，不会在中途归零后再加速
            exitScale.animateTo(0f, spring(dampingRatio = 1f, stiffness = 380f))
            finish()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 收缩成小圆的过程中底板同步淡出，露出下层界面
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val circleProgress = ((1f - exitScale.value) / 0.84f).coerceIn(0f, 1f)
                    alpha = 1f - ((circleProgress - 0.35f) / 0.65f).coerceIn(0f, 1f)
                }
                .background(MiuixTheme.colorScheme.background)
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = exitScale.value
                    scaleY = exitScale.value
                    transformOrigin = TransformOrigin.Center
                    // 缩到 0.16 的过程中圆角同步增长到半径，形成一个小圆
                    val circleProgress = ((1f - exitScale.value) / 0.84f).coerceIn(0f, 1f)
                    clip = circleProgress > 0f
                    shape = RoundedCornerShape(size.minDimension / 2f * circleProgress)
                }
        ) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            AppBackground(
                selection = "",
                color = MiuixTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                // 协议页必须同意后才能继续，因此不显示跳过
                if (pagerState.currentPage != 0) {
                    Text(
                        text = "跳过",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !exiting) { finish() }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                // 倒计时结束前禁止滑动翻页
                userScrollEnabled = pagerState.currentPage != 0 || agreementRemaining == 0
            ) { page ->
                when (page) {
                    0 -> OnboardAgreementPage()
                    1 -> OnboardWelcomePage()
                    2 -> OnboardAppearancePage(
                        theme = theme,
                        onThemeChange = ::applyTheme,
                        liquidGlass = liquidGlass,
                        onLiquidGlassChange = { enabled ->
                            liquidGlass = enabled
                            prefs.liquidGlass = enabled
                            if (enabled) applyTheme(AppPrefs.MODE_DARK)
                        },
                        backdrop = backdrop
                    )

                    3 -> OnboardServicePage(
                        autoRunService = autoRunService,
                        onAutoRunServiceChange = {
                            autoRunService = it
                            prefs.autoRunService = it
                        },
                        keepAlive = keepAlive,
                        onKeepAliveChange = {
                            keepAlive = it
                            prefs.keepAlive = it
                        }
                    )

                    else -> OnboardPermissionPage(
                        notifGranted = notifGranted,
                        onRequestNotif = {
                            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onRequestBattery = { openBatteryOptimizationSettings(context) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(ONBOARD_PAGES) { index ->
                    val active = pagerState.currentPage == index
                    val dotSize by animateDpAsState(
                        targetValue = if (active) 9.dp else 7.dp,
                        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                        label = "onboardDotSize"
                    )
                    val dotColor by animateColorAsState(
                        targetValue = if (active) {
                            StateAccent
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
                        },
                        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                        label = "onboardDotColor"
                    )
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(dotSize)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            val lastPage = pagerState.currentPage == ONBOARD_PAGES - 1
            val onAgreementPage = pagerState.currentPage == 0
            Button(
                onClick = {
                    if (lastPage) {
                        playExitAndFinish()
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                enabled = !onAgreementPage || agreementRemaining == 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(50.dp),
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                    color = StateAccent,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = when {
                        lastPage -> "开始使用"
                        onAgreementPage ->
                            if (agreementRemaining > 0) "确认（${agreementRemaining}s）" else "确认"

                        else -> "下一步"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(20.dp))
        }
        }
    }
}

/** 通知权限是否已授予（Android 13 以下默认视为已授予） */
private fun hasPostNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** 引导页统一版式：图标 + 标题 + 说明 + 内容区，内容可滚动 */
@Composable
private fun OnboardPage(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(StateAccent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = StateAccent,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.height(24.dp))
        content()
        Spacer(Modifier.height(24.dp))
    }
}

/** 协议页：版式对齐手表端「请先同意协议」，正文可滚动，确认按钮在引导页底部 */
@Composable
private fun OnboardAgreementPage() {
    OnboardPage(
        icon = Icons.Rounded.Shield,
        title = "请先同意协议",
        subtitle = "请仔细阅读以下条款，确认后即可继续使用 BiliMax。"
    ) {
        Text(
            text = "《BiliMax 用户协议与隐私政策》",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        AgreementParagraph(
            title = "一、服务说明",
            body = "BiliMax 是一款把手机端视频同步到手表端的本地工具。视频转码、切片与传输均在您的设备上完成，" +
                "我们不会将您的视频内容上传到任何服务器。"
        )
        AgreementParagraph(
            title = "二、信息收集",
            body = "为提供同步功能，本应用会读取您主动选择的视频文件、存储目录与设备连接状态；" +
                "这些数据仅保存在本机，不会用于数据分析或广告用途。"
        )
        AgreementParagraph(
            title = "三、权限使用",
            body = "通知权限：用于显示转码与同步进度提醒；" +
                "存储权限：用于读取待同步的视频并写入临时缓存；" +
                "电池优化白名单：用于保证后台同步服务不被系统回收。" +
                "您可以在系统设置中随时撤销以上权限。"
        )
        AgreementParagraph(
            title = "四、第三方组件",
            body = "当您使用互联 SDK 时，视频会通过小米运动健康等配套服务传输到手表设备，" +
                "该过程同时受对应服务的隐私政策约束。"
        )
        AgreementParagraph(
            title = "五、数据留存",
            body = "应用产生的缓存与日志仅保存在本机，卸载应用即会清除，我们不提供任何云端账号与数据存储。"
        )
        AgreementParagraph(
            title = "六、协议更新",
            body = "我们可能会随版本迭代调整本协议，更新后会在引导页或应用内提示，继续使用即视为同意最新版本。"
        )
        Text(
            text = "声明",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = AgreementBlue
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "不建议使用 AstroBox 等第三方网络共享应用为手表提供网络（同步信息除外），" +
                "推荐使用【小米运动健康】或 eSIM 进行网络连接；如果连接使用时出现问题，请断开后重试。",
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = AgreementBlue
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "使用本应用前请保证您的设备存储空间充足，如果遇到无法写入的问题，" +
                "请重启设备或尝试清理存储空间后重试！",
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = AgreementRed
        )
    }
}

@Composable
private fun AgreementParagraph(title: String, body: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = body,
        fontSize = 12.sp,
        lineHeight = 19.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun OnboardWelcomePage() {
    OnboardPage(
        icon = Icons.Rounded.RocketLaunch,
        title = "欢迎使用 BiliMax 助手",
        subtitle = "把手机上的视频同步到手表，随时随地离线观看。先花一分钟完成初始设置。"
    ) {
        OnboardInfoRow(
            icon = Icons.Rounded.Watch,
            title = "手表视频同步",
            summary = "通过蓝牙或互联 SDK 把视频推送到手表端播放"
        )
        Spacer(Modifier.height(12.dp))
        OnboardInfoRow(
            icon = Icons.Rounded.Movie,
            title = "本地转码",
            summary = "自动把视频转成手表能流畅播放的规格"
        )
        Spacer(Modifier.height(12.dp))
        OnboardInfoRow(
            icon = Icons.Rounded.Bluetooth,
            title = "互联 SDK",
            summary = "通过小米运动健康等配套服务，把视频拉取到小米手表 / 手环"
        )
    }
}

@Composable
private fun OnboardAppearancePage(
    theme: String,
    onThemeChange: (String) -> Unit,
    liquidGlass: Boolean,
    onLiquidGlassChange: (Boolean) -> Unit,
    backdrop: Backdrop
) {
    OnboardPage(
        icon = Icons.Rounded.DarkMode,
        title = "选择外观",
        subtitle = "之后可以在「设置」里随时修改。"
    ) {
        Text(
            text = "主题",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        SegmentedSlider(
            options = listOf("跟随系统", "日间", "夜间"),
            selectedIndex = when (theme) {
                AppPrefs.MODE_LIGHT -> 1
                AppPrefs.MODE_DARK -> 2
                else -> 0
            },
            onSelected = { index ->
                onThemeChange(
                    when (index) {
                        1 -> AppPrefs.MODE_LIGHT
                        2 -> AppPrefs.MODE_DARK
                        else -> AppPrefs.MODE_SYSTEM
                    }
                )
            },
            enabled = !liquidGlass,
            glass = liquidGlass,
            backdrop = backdrop
        )
        Spacer(Modifier.height(16.dp))
        OnboardToggleRow(
            title = "液态玻璃",
            summary = if (liquidGlass) {
                "已开启：界面为半透明玻璃质感，主题锁定夜间"
            } else {
                "开启后界面切换为深色玻璃质感"
            },
            checked = liquidGlass,
            onChange = onLiquidGlassChange
        )
    }
}

@Composable
private fun OnboardServicePage(
    autoRunService: Boolean,
    onAutoRunServiceChange: (Boolean) -> Unit,
    keepAlive: Boolean,
    onKeepAliveChange: (Boolean) -> Unit
) {
    OnboardPage(
        icon = Icons.Rounded.Settings,
        title = "后台服务",
        subtitle = "同步服务需要常驻后台，手表发起请求时才能立即响应。"
    ) {
        OnboardToggleRow(
            title = "开机自动启动",
            summary = "开机后自动拉起同步服务",
            checked = autoRunService,
            onChange = onAutoRunServiceChange
        )
        Spacer(Modifier.height(12.dp))
        OnboardToggleRow(
            title = "常驻后台",
            summary = "避免服务被系统回收，保证手表随时能拉取视频",
            checked = keepAlive,
            onChange = onKeepAliveChange
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "开启后建议同时在系统设置中允许本应用自启动，避免被省电策略限制。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

@Composable
private fun OnboardPermissionPage(
    notifGranted: Boolean,
    onRequestNotif: () -> Unit,
    onRequestBattery: () -> Unit
) {
    OnboardPage(
        icon = Icons.Rounded.Shield,
        title = "开启必要权限",
        subtitle = "通知权限用于展示同步进度；关闭电池优化可避免服务被系统冻结。"
    ) {
        OnboardActionRow(
            icon = Icons.Rounded.Notifications,
            title = "通知权限",
            summary = if (notifGranted) "已授权" else "未授权，无法显示同步进度",
            actionLabel = if (notifGranted) null else "去授权",
            onAction = onRequestNotif
        )
        Spacer(Modifier.height(12.dp))
        OnboardActionRow(
            icon = Icons.Rounded.Shield,
            title = "不受电池优化限制",
            summary = "避免后台服务被系统冻结导致下载失败",
            actionLabel = "去设置",
            onAction = onRequestBattery
        )
    }
}

@Composable
private fun OnboardInfoRow(
    icon: ImageVector,
    title: String,
    summary: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = StateAccent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun OnboardToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.switchColors(checkedTrackColor = StateAccent)
        )
    }
}

@Composable
private fun OnboardActionRow(
    icon: ImageVector,
    title: String,
    summary: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = StateAccent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        if (actionLabel != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = actionLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = StateAccent,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onAction() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun AppRoot(
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onReplayOnboarding: () -> Unit
) {
    val context = LocalContext.current
    val rootPagerState = rememberPagerState(initialPage = RootTab.HOME.ordinal) { RootTab.entries.size }
    // liquidGlass：底栏液态玻璃开关。设置页切换后实时生效，同时持久化。
    var liquidGlass by remember { mutableStateOf(AppPrefs.get(context).liquidGlass) }
    // 当前背景图：空串为默认（主题底色），否则为 filesDir/backgrounds 下的文件名
    var backgroundSelection by remember { mutableStateOf(AppPrefs.get(context).backgroundSelection) }
    // 背景图模糊化程度（dp），背景图设置页的滑块实时改这个值
    var backgroundBlur by remember { mutableFloatStateOf(AppPrefs.get(context).backgroundBlur) }
    val hasCustomBackground = backgroundSelection.isNotBlank()
    // 玻璃调参：启动时从偏好载入一次，之后由液态玻璃实验室页实时修改
    remember { GlassTuning.load(AppPrefs.get(context)) }

    // 液态玻璃与夜间主题绑定：已开启液态玻璃时，主题必须锁定为夜间。
    // 启动时若发现偏好不一致（例如旧版本残留），在此一次性校正。
    LaunchedEffect(Unit) {
        if (liquidGlass && themeMode != AppPrefs.MODE_DARK) {
            AppPrefs.get(context).themeMode = AppPrefs.MODE_DARK
            onThemeModeChange(AppPrefs.MODE_DARK)
        }
    }

    // pageStack：全屏子页面导航栈（转码/历史/传输/日志/关于）。
    // 从首页进入的页面重置栈；从设置进入的关于压栈，返回时回到设置页。
    val pageStack = remember { mutableStateListOf<SyncSubPage>() }
    // closingPage：退场动画期间正在关闭的页面。动画结束弹栈后置空。
    var closingPage by remember { mutableStateOf<SyncSubPage?>(null) }
    val scope = rememberCoroutineScope()
    fun selectRootTab(tab: RootTab) {
        scope.launch { rootPagerState.animateScrollToPage(tab.ordinal) }
    }
    // backProgress：返回进度 0=子页面完整覆盖，1=完全滑出
    val backProgress = remember { Animatable(0f) }

    val animSpec = tween<Float>(durationMillis = 320, easing = FastOutSlowInEasing)

    // 当前正在展示的页面：退场动画期间优先显示正在关闭的页面，否则取栈顶
    val displayedPage: SyncSubPage? = closingPage ?: pageStack.lastOrNull()

    // 打开全屏页面（从首页进入）：重置栈为该页面
    fun openRootPage(page: SyncSubPage) {
        closingPage = null
        pageStack.clear()
        pageStack.add(page)
        scope.launch {
            backProgress.snapTo(1f)
            backProgress.animateTo(0f, animSpec)
        }
    }

    // 压入全屏页面（从设置进入关于）：保留设置 Tab 在底层
    fun pushSubPage(page: SyncSubPage) {
        closingPage = null
        pageStack.add(page)
        scope.launch {
            backProgress.snapTo(1f)
            backProgress.animateTo(0f, animSpec)
        }
    }

    fun requestCloseSubPage() {
        val top = pageStack.lastOrNull()
        if (top != null) closingPage = top
        scope.launch {
            backProgress.animateTo(1f, animSpec)
            if (pageStack.isNotEmpty()) pageStack.removeAt(pageStack.lastIndex)
            closingPage = null
            backProgress.snapTo(0f)
        }
    }

    BackHandler(enabled = displayedPage != null) {
        requestCloseSubPage()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val p = backProgress.value
        val page = displayedPage
        val cover = if (page != null) 1f - p else 0f
        val underScale = 1f - 0.06f * cover
        val backdrop = rememberLayerBackdrop()
        val barBackdrop = rememberLayerBackdrop()
        val backgroundColor = MiuixTheme.colorScheme.background
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            ) {
                AppBackground(
                    selection = backgroundSelection,
                    color = backgroundColor,
                    blurDp = backgroundBlur,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(barBackdrop)
                    .graphicsLayer {
                        scaleX = underScale
                        scaleY = underScale
                        // 子页面打开时隐藏一级页面，避免透明子页面与首页内容图层叠加
                        alpha = if (displayedPage == null) 1f - 0.5f * cover else 0f
                    }
            ) {
                HorizontalPager(
                    state = rootPagerState,
                    userScrollEnabled = displayedPage == null,
                    modifier = Modifier.fillMaxSize()
                ) { pageIndex ->
                    when (RootTab.entries[pageIndex]) {
                        RootTab.HOME -> SyncScreen(
                            onOpenSubPage = { openRootPage(it) },
                            liquidGlass = liquidGlass,
                            hasBackground = hasCustomBackground,
                            backdrop = backdrop
                        )
                        RootTab.SETTINGS -> SettingsScreen(
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            onOpenSubPage = { pushSubPage(it) },
                            liquidGlass = liquidGlass,
                            backdrop = backdrop,
                            backgroundSelection = backgroundSelection,
                            onLiquidGlassChange = { enabled ->
                                liquidGlass = enabled
                                AppPrefs.get(context).liquidGlass = enabled
                                if (enabled) {
                                    AppPrefs.get(context).themeMode = AppPrefs.MODE_DARK
                                    onThemeModeChange(AppPrefs.MODE_DARK)
                                }
                            },
                            onReplayOnboarding = onReplayOnboarding
                        )
                    }
                }
            }
        }

        if (page != null) {
            // 返回过程中的暗色遮罩：位于 Tab 内容之上、所有子页面之下，随栈页滑出渐隐
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x59000000))
                    .graphicsLayer { alpha = cover }
            )
            // 页面栈层叠渲染：从栈底到栈顶依次堆叠。下层页面静止在位移 0，
            // 只有当前栈顶（或退场中的 closingPage）做滑入/滑出，这样从
            // 二级页（关于）返回时，滑出后露出的是下层进入前的界面。
            val renderedStack: List<SyncSubPage> =
                if (closingPage != null && !pageStack.contains(closingPage)) {
                    pageStack + listOf(closingPage!!)
                } else {
                    pageStack.toList()
                }
            renderedStack.forEachIndexed { index, item ->
                // 栈顶页（含退场页）跟随 backProgress 滑动；其余下层页静止盖住底层
                val isTop = index == renderedStack.lastIndex
                val translateX = if (isTop) pageWidthPx * p else 0f
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = translateX
                            shadowElevation = if (isTop) 12f else 0f
                        }
                ) {
                    SubPageContent(
                        page = item,
                        onBack = { requestCloseSubPage() },
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        onOpenSubPage = { pushSubPage(it) },
                        backgroundSelection = backgroundSelection,
                        onBackgroundChange = { name ->
                            backgroundSelection = name
                            AppPrefs.get(context).backgroundSelection = name
                        },
                        backgroundBlur = backgroundBlur,
                        onBackgroundBlurChange = { value ->
                            // 取整，避免拖动时逐帧给整张大图换模糊半径
                            val rounded = value.roundToInt().toFloat()
                            backgroundBlur = rounded
                            AppPrefs.get(context).backgroundBlur = rounded
                        },
                        liquidGlass = liquidGlass,
                        backdrop = backdrop
                    )
                }
            }
        }

        // 悬浮胶囊底栏：仅在无全屏子页面时显示，固定叠加于内容底部
        if (page == null) {
            CapsuleBottomBar(
                pagerState = rootPagerState,
                onTabSelected = { selectRootTab(it) },
                // 生效范围里的「选择器」关闭时，底栏回到实心样式
                liquidGlass = liquidGlass && GlassTuning.scopeSelector,
                backdrop = barBackdrop,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
      }
    }
}

// 底栏一级界面：首页 / 设置
private enum class RootTab { HOME, SETTINGS }

// 全屏子页面（从首页或设置进入，带返回箭头）
private enum class SyncSubPage { TRANSCODE, HISTORY, TRANSFER, LOGS, ABOUT, LIQUID_GLASS, BACKGROUND }

// 子页面分发：根据页面枚举渲染对应全屏页面
@Composable
private fun SubPageContent(
    page: SyncSubPage,
    onBack: () -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onOpenSubPage: (SyncSubPage) -> Unit,
    backgroundSelection: String,
    onBackgroundChange: (String) -> Unit,
    backgroundBlur: Float,
    onBackgroundBlurChange: (Float) -> Unit,
    liquidGlass: Boolean,
    backdrop: Backdrop
) {
    // 子页面内的卡片统一走 SubPageCard，玻璃开关与背景层由此下发
    CompositionLocalProvider(
        LocalSubPageGlass provides liquidGlass,
        LocalSubPageBackdrop provides backdrop
    ) {
        when (page) {
            SyncSubPage.TRANSCODE -> TranscodeScreen(onBack = onBack)
            SyncSubPage.TRANSFER -> ManualTransferScreen(onBack = onBack)
            SyncSubPage.HISTORY -> HistoryScreen(onBack = onBack)
            SyncSubPage.LOGS -> SyncLogScreen(onBack = onBack)
            SyncSubPage.ABOUT -> AboutScreen(onBack = onBack)
            SyncSubPage.LIQUID_GLASS -> LiquidGlassScreen(onBack = onBack)
            SyncSubPage.BACKGROUND -> BackgroundScreen(
                selection = backgroundSelection,
                onSelectionChange = onBackgroundChange,
                blur = backgroundBlur,
                onBlurChange = onBackgroundBlurChange,
                onBack = onBack
            )
        }
    }
}

// ============================== 液态玻璃实时调参 ==============================

// 单例持有 Compose 状态：任何读取处都会在参数变化时自动重组。
// 玻璃参数分三组（滑块 / 导轨 / 触控按钮），各组互不影响，
// 数值持久化在 AppPrefs，启动时 load 一次，拖动结束后 save 一次。

/** 单项参数：用于判断某个分组是否真的绘制了这项效果 */
internal enum class GlassParam {
    BLUR, TINT, LENS_HEIGHT, LENS_AMOUNT, DEPTH, CHROMATIC, HIGHLIGHT, INNER_SHADOW, VIBRANCY, BOUNCY
}

/** 调参分组：决定这次改的是哪一类玻璃件的参数 */
internal enum class GlassGroup(val key: String, val label: String, val desc: String) {
    THUMB("thumb", "滑块", "底栏上滑动选择的块、设置页分段选择的选中块"),
    TRACK("track", "导轨", "底栏的导轨、设置页分段选择的轨道"),
    BUTTON("button", "触控按钮", "首页按钮卡片、设置页选项卡片与开关");

    /** 该组玻璃件是否绘制了这项参数；没绘制的在实验室里置灰，避免“拖了没变化” */
    fun supports(param: GlassParam): Boolean = when (param) {
        // 底栏滑块与分段控件的选中块只画折射、高光与着色，没有内阴影
        GlassParam.INNER_SHADOW -> this != THUMB
        else -> true
    }
}

/** 一组玻璃参数 */
internal class GlassSpec {
    var blurDp by mutableStateOf(8f)
    var lensHeightDp by mutableStateOf(18f)
    var lensAmountDp by mutableStateOf(20f)
    var tintAlpha by mutableStateOf(0.04f)
    var chromatic by mutableStateOf(true)
    var depth by mutableStateOf(false)
    var vibrancy by mutableStateOf(true)
    var highlight by mutableStateOf(true)
    var innerShadow by mutableStateOf(true)
    var bouncy by mutableStateOf(true)

    fun encode(): String = listOf(
        blurDp, lensHeightDp, lensAmountDp, tintAlpha,
        chromatic, depth, vibrancy, highlight, innerShadow, bouncy
    ).joinToString(",")

    fun decode(raw: String) {
        val f = raw.split(',')
        if (f.size < 10) return
        f[0].toFloatOrNull()?.let { blurDp = it }
        f[1].toFloatOrNull()?.let { lensHeightDp = it }
        f[2].toFloatOrNull()?.let { lensAmountDp = it }
        f[3].toFloatOrNull()?.let { tintAlpha = it }
        f[4].toBooleanStrictOrNull()?.let { chromatic = it }
        f[5].toBooleanStrictOrNull()?.let { depth = it }
        f[6].toBooleanStrictOrNull()?.let { vibrancy = it }
        f[7].toBooleanStrictOrNull()?.let { highlight = it }
        f[8].toBooleanStrictOrNull()?.let { innerShadow = it }
        f[9].toBooleanStrictOrNull()?.let { bouncy = it }
    }

    fun applyPreset(index: Int) {
        when (index) {
            // 省电：关闭高成本效果，只保留最轻的折射
            0 -> {
                blurDp = 2f; lensHeightDp = 10f; lensAmountDp = 10f
                chromatic = false; depth = false; vibrancy = false
                tintAlpha = 0.02f; highlight = false; innerShadow = false; bouncy = false
            }
            // 极致：更强模糊与折射，开启深度与色散
            2 -> {
                blurDp = 12f; lensHeightDp = 30f; lensAmountDp = 36f
                chromatic = true; depth = true; vibrancy = true
                tintAlpha = 0.06f; highlight = true; innerShadow = true; bouncy = true
            }
            else -> {
                blurDp = 8f; lensHeightDp = 18f; lensAmountDp = 20f
                chromatic = true; depth = false; vibrancy = true
                tintAlpha = 0.04f; highlight = true; innerShadow = true; bouncy = true
            }
        }
    }
}

internal object GlassTuning {
    /** 滑块 */
    val thumb = GlassSpec().apply {
        blurDp = 0f; lensHeightDp = 10f; lensAmountDp = 14f; tintAlpha = 0.03f
    }

    /** 导轨 */
    val track = GlassSpec()

    /** 触控按钮 */
    val button = GlassSpec().apply {
        blurDp = 0f; lensHeightDp = 18f; lensAmountDp = 18f; tintAlpha = 0.03f
    }

    /** 当前调参分组 */
    var editing by mutableStateOf(GlassGroup.THUMB)

    // 预设档位：同时作用于三组
    var presetIndex by mutableStateOf(1)

    /** 生效范围：卡片类（首页卡片、设置页卡片、子页面卡片等） */
    var scopeCard by mutableStateOf(true)

    /** 生效范围：选择器类（底栏导轨/滑块、分段选择的轨道与滑块） */
    var scopeSelector by mutableStateOf(true)

    fun spec(group: GlassGroup): GlassSpec = when (group) {
        GlassGroup.THUMB -> thumb
        GlassGroup.TRACK -> track
        GlassGroup.BUTTON -> button
    }

    fun load(p: AppPrefs) {
        // 老版本只有一组全局参数，首次升级时作为三组的初始值
        val legacy = listOf(
            p.glassBlur, p.glassLensHeight, p.glassLensAmount, p.glassTint,
            p.glassChromatic, p.glassDepth, p.glassVibrancy,
            p.glassHighlight, p.glassInnerShadow, p.glassBouncy
        ).joinToString(",")
        GlassGroup.entries.forEach { g ->
            val raw = p.glassSpecOf(g.key)
            if (raw.isBlank()) spec(g).decode(legacy) else spec(g).decode(raw)
        }
        scopeCard = p.glassScopeCard
        scopeSelector = p.glassScopeSelector
    }

    fun save(p: AppPrefs) {
        GlassGroup.entries.forEach { g -> p.setGlassSpec(g.key, spec(g).encode()) }
    }

    /** 保存生效范围（卡片 / 选择器） */
    fun saveScope(p: AppPrefs) {
        p.glassScopeCard = scopeCard
        p.glassScopeSelector = scopeSelector
    }

    fun applyPreset(index: Int, p: AppPrefs) {
        presetIndex = index
        GlassGroup.entries.forEach { spec(it).applyPreset(index) }
        save(p)
    }
}

/** 卡片/按钮类玻璃件的内阴影：半径与偏移都小，避免大卡片顶端出现整条灰带 */
private val CardInnerShadow = InnerShadow(
    radius = 5.dp,
    offset = DpOffset(0.dp, 3.dp),
    color = Color.Black.copy(alpha = 0.10f)
)

// ============================== 悬浮胶囊底栏 ==============================

@Composable
private fun CapsuleBottomBar(
    pagerState: PagerState,
    onTabSelected: (RootTab) -> Unit,
    liquidGlass: Boolean,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val shape = Capsule()
    val tabs = RootTab.entries
    // 玻璃参数快照：导轨用 track 组，滑块用 thumb 组，在组合期读取以便实时重组
    val rail = GlassTuning.track
    val knob = GlassTuning.thumb
    val glassBouncy = rail.bouncy
    val knobBouncy = knob.bouncy
    val outerSolidColor = MiuixTheme.colorScheme.surface.copy(alpha = 0.82f)
    val outerGlassColor = MiuixTheme.colorScheme.surface.copy(alpha = rail.tintAlpha)
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val neutralColor = if (isDark) Color.White else Color.Black
    val selectionProgress = pagerState.currentPage + pagerState.currentPageOffsetFraction
    val dragProgress = kotlin.math.abs(pagerState.currentPageOffsetFraction)
    val reveal = remember { Animatable(0f) }
    var barPressed by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    var hasTouch by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val railPressScale by animateFloatAsState(
        targetValue = if (liquidGlass && barPressed) 1.08f else 1f,
        animationSpec = spring(
            dampingRatio = if (glassBouncy) Spring.DampingRatioMediumBouncy else 1f,
            stiffness = 1000f
        ),
        label = "glassRailScale"
    )
    val knobPressScale by animateFloatAsState(
        targetValue = if (liquidGlass && barPressed) 1.28f else 1f,
        animationSpec = spring(
            dampingRatio = if (knobBouncy) Spring.DampingRatioMediumBouncy else 1f,
            stiffness = 1000f
        ),
        label = "glassKnobScale"
    )

    LaunchedEffect(barPressed, dragProgress, liquidGlass) {
        val target = if (liquidGlass && (barPressed || dragProgress > 0.01f)) 1f else 0f
        reveal.animateTo(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = 1f,
                stiffness = 1000f
            )
        )
    }

    BoxWithConstraints(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 8.dp)
            .height(68.dp)
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .pointerInput(liquidGlass, pagerState) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressedNow = event.changes.any { it.pressed }
                        val position = event.changes.firstOrNull { it.pressed && it.positionChanged() }?.position
                        if (position != null) {
                            touch = position
                            hasTouch = true
                        }
                        barPressed = pressedNow
                    }
                }
            }
            .pointerInput(pagerState) {
                // 单消费者通道：手势回调只投递横向增量，唯一的消费者协程顺序
                // 派发 dispatchRawDelta，避免逐帧 launch 造成的协程排队/抢占
                val deltaChannel = Channel<Float>(capacity = Channel.UNLIMITED)
                val consumer = scope.launch {
                    for (delta in deltaChannel) {
                        pagerState.dispatchRawDelta(delta)
                    }
                }
                val dragSensitivity = 1.6f
                detectHorizontalDragGestures(
                    onDragStart = { barPressed = true },
                    onDragEnd = {
                        barPressed = false
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage) }
                    },
                    onDragCancel = {
                        barPressed = false
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage) }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val pageWidth = pagerState.layoutInfo.pageSize
                        if (pageWidth > 0) {
                            deltaChannel.trySend(dragAmount * dragSensitivity * pageWidth / size.width)
                        }
                    }
                )
                consumer.cancel()
                deltaChannel.close()
            }
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(64.dp)
                .graphicsLayer {
                    scaleX = railPressScale
                    scaleY = railPressScale
                }
                .clip(shape)
                .then(
                    if (liquidGlass) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                if (rail.vibrancy) vibrancy()
                                if (rail.blurDp > 0f) blur(rail.blurDp.dp.toPx())
                                lens(
                                    rail.lensHeightDp.dp.toPx(),
                                    rail.lensAmountDp.dp.toPx(),
                                    rail.depth,
                                    rail.chromatic
                                )
                            },
                            highlight = {
                                if (rail.highlight) Highlight.Default else Highlight.Plain.copy(alpha = 0f)
                            },
                            innerShadow = {
                                if (rail.innerShadow) InnerShadow.Default else InnerShadow.Default.copy(alpha = 0f)
                            },
                            onDrawSurface = { drawRect(outerGlassColor) }
                        )
                    } else {
                        Modifier.background(outerSolidColor)
                    }
                )
        )

        val tabWidth = maxWidth - 8.dp
        val selectedPosition = selectionProgress.coerceIn(0f, (tabs.size - 1).toFloat())
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
                .width(tabWidth / tabs.size)
                .height(56.dp)
                .graphicsLayer {
                    translationX = selectedPosition * size.width
                    scaleX = knobPressScale
                    scaleY = knobPressScale
                }
                .clip(shape)
                .then(
                    if (liquidGlass) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                if (knob.vibrancy) vibrancy()
                                if (knob.blurDp > 0f) blur(knob.blurDp.dp.toPx())
                                lens(
                                    refractionHeight = knob.lensHeightDp.dp.toPx(),
                                    refractionAmount = knob.lensAmountDp.dp.toPx(),
                                    depthEffect = knob.depth,
                                    chromaticAberration = knob.chromatic
                                )
                            },
                            highlight = {
                                if (knob.highlight) Highlight.Default else Highlight.Plain.copy(alpha = 0f)
                            },
                            onDrawSurface = {
                                drawRect(neutralColor.copy(alpha = knob.tintAlpha))
                            }
                        )
                    } else {
                        Modifier.drawBehind {
                            drawRoundRect(
                                color = neutralColor.copy(alpha = 0.08f),
                                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                            )
                        }
                    }
                )
                .then(
                    if (liquidGlass) {
                        Modifier.drawBehind {
                            val glowCenter = if (hasTouch) {
                                Offset(
                                    x = (touch.x - 4.dp.toPx() - selectedPosition * size.width).coerceIn(0f, size.width),
                                    y = touch.y.coerceIn(0f, size.height)
                                )
                            } else {
                                Offset(size.width / 2f, size.height / 2f)
                            }
                            val radius = minOf(size.width, size.height) * 1.5f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        neutralColor.copy(alpha = 0.15f * reveal.value),
                                        Color.Transparent
                                    ),
                                    center = glowCenter,
                                    radius = radius
                                ),
                                radius = radius,
                                center = glowCenter
                            )
                            drawRoundRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        neutralColor.copy(alpha = 0.50f * reveal.value),
                                        Color.Transparent
                                    )
                                ),
                                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        )

        Row(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                CapsuleTabItem(
                    tab = tab,
                    selectedAmount = when (tab) {
                        RootTab.HOME -> 1f - selectedPosition
                        RootTab.SETTINGS -> selectedPosition
                    }.coerceIn(0f, 1f),
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RowScope.CapsuleTabItem(
    tab: RootTab,
    selectedAmount: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val selectedColor = if (isDark) Color(0xFF7FCFFF) else Color(0xFF3FA9F5)
    val unselectedColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.60f)
    val contentColor = lerp(unselectedColor, selectedColor, selectedAmount)
    val icon = when (tab) {
        RootTab.HOME -> Icons.Rounded.Home
        RootTab.SETTINGS -> Icons.Rounded.Settings
    }
    val label = when (tab) {
        RootTab.HOME -> "首页"
        RootTab.SETTINGS -> "设置"
    }
    Row(
        modifier
            .fillMaxHeight()
            .clip(Capsule())
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor
        )
    }
}

@Composable
private fun SyncScreen(
    onOpenSubPage: (SyncSubPage) -> Unit,
    liquidGlass: Boolean,
    hasBackground: Boolean,
    backdrop: Backdrop
) {
    val context = LocalContext.current
    var serviceRunning by remember { mutableStateOf(SyncHolder.isServerRunning()) }

    // 手表连接状态（XMS 消息通道）：订阅 WearTransport，回调在主线程触发
    var wearState by remember { mutableStateOf(WearState.DISCONNECTED) }
    var wearDeviceName by remember { mutableStateOf<String?>(null) }
    val wearTransport = remember { WearTransport.get(context) }
    var wearChannel by remember { mutableStateOf(WearTransport.backendChannel) }
    DisposableEffect(Unit) {
        val listener = WearTransport.OnStateChanged { state, deviceName, _ ->
            wearState = state
            wearDeviceName = deviceName
            wearChannel = WearTransport.backendChannel
        }
        wearTransport.addOnStateChanged(listener)
        onDispose { wearTransport.removeOnStateChanged(listener) }
    }

    var showStopDialog by remember { mutableStateOf(false) }
    var showQqGroupDialog by remember { mutableStateOf(false) }

    // 页面每次回到前台都以 HTTP 服务器实际状态为准刷新，防止卡片显示与实际脱节
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // 服务器启停为异步（后台线程绑定端口），订阅状态回调：启动/停止完成后
        // 在主线程可靠更新卡片，避免「关闭后再开启」界面卡在未运行状态
        val serverListener = SyncHolder.OnServerStateChanged { running ->
            serviceRunning = running
        }
        SyncHolder.addOnServerStateChanged(serverListener)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                serviceRunning = SyncHolder.isServerRunning()
                // 已连接/连接中时回前台不发起重扫：瞬时的空节点查询会把健康连接误显为
                // 「未连接」；后台自动重连与手动点击卡片仍会触发扫描
                if (wearTransport.currentState() == WearState.DISCONNECTED) {
                    wearTransport.ensureConnection()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            SyncHolder.removeOnServerStateChanged(serverListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = "BiliMax 助手",
                color = if (liquidGlass || hasBackground) Color.Transparent else MiuixTheme.colorScheme.surface,
                scrollBehavior = scrollBehavior
            )
        },
        containerColor = if (liquidGlass || hasBackground) Color.Transparent else MiuixTheme.colorScheme.surface
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
                item {
                    // 两栏等宽布局（参考 Shell++）：
                    // 左栏：转码服务开关（大正方形）+ 目标设备（大正方形，带蓝牙标志）
                    // 右栏：手动转码 / 转码历史 / 手动传输 / 查看日志 四个等距入口，
                    //       上下与左栏平齐（aspectRatio(2f) 的高度 = 两个正方形 + 中间间距）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 左栏
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ServiceCard(
                                title = "转码服务",
                                statusText = if (serviceRunning) "运行中" else "未运行",
                                detail = if (!serviceRunning) {
                                    "点击开启 HTTP 服务"
                                } else {
                                    // 蜂窝下没有可被手表直连的局域网地址，但服务仍在 0.0.0.0 监听，
                                    // 手表走云端中继 / SF 桥接访问；此时不能再显示「等待网络」，
                                    // 否则用户会误以为手机在移动数据下无法当转码服务器。
                                    NetInfo.lanIpv4()?.let { "$it:${TranscodeServer.PORT}" }
                                        ?: NetInfo.reportedIpv4()?.let { "蜂窝网络 · 云端中继" }
                                        ?: "等待网络"
                                },
                                accent = if (serviceRunning) StateSuccess else StateDanger,
                                backgroundIcon = if (serviceRunning) Icons.Rounded.StopCircle else Icons.Rounded.PlayCircle,
                                onClick = {
                                    if (SyncHolder.isServerRunning()) {
                                        showStopDialog = true
                                    } else {
                                        SyncHolder.setServerEnabled(true)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                                glass = liquidGlass,
                                hasBackground = hasBackground,
                                backdrop = backdrop
                            )
                            TargetDeviceSquareCard(
                                connected = wearState == WearState.CONNECTED,
                                connecting = wearState == WearState.CONNECTING,
                                deviceName = wearDeviceName?.takeIf { it.isNotEmpty() },
                                channelLabel = when (AppPrefs.get(context).sdkBackend) {
                                    AppPrefs.SDK_BACKEND_XIAOMI -> "小米运动健康"
                                    AppPrefs.SDK_BACKEND_ORONBOX -> "Oronbox"
                                    else -> wearChannel.label
                                },
                                onClick = { wearTransport.ensureConnection() },
                                glass = liquidGlass,
                                hasBackground = hasBackground,
                                backdrop = backdrop
                            )
                        }
                        // 右栏：四个等距入口按钮，总高度与左栏平齐
                        // （两个正方形 + 12dp 间距，weight(1f) 平分高度）
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ActionEntryButton(
                                icon = Icons.Rounded.AutoAwesome,
                                label = "手动转码",
                                accent = StateAccent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                glass = liquidGlass,
                                hasBackground = hasBackground,
                                backdrop = backdrop,
                                onClick = { onOpenSubPage(SyncSubPage.TRANSCODE) }
                            )
                            ActionEntryButton(
                                icon = Icons.Rounded.History,
                                label = "转码历史",
                                accent = StatePurple,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                glass = liquidGlass,
                                hasBackground = hasBackground,
                                backdrop = backdrop,
                                onClick = { onOpenSubPage(SyncSubPage.HISTORY) }
                            )
                            ActionEntryButton(
                                icon = Icons.Rounded.SwapHoriz,
                                label = "手动传输",
                                accent = StateSuccess,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                glass = liquidGlass,
                                hasBackground = hasBackground,
                                backdrop = backdrop,
                                onClick = { onOpenSubPage(SyncSubPage.TRANSFER) }
                            )
                            ActionEntryButton(
                                icon = Icons.Rounded.Terminal,
                                label = "查看日志",
                                accent = StateWarning,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                glass = liquidGlass,
                                hasBackground = hasBackground,
                                backdrop = backdrop,
                                onClick = { onOpenSubPage(SyncSubPage.LOGS) }
                            )
                        }
                    }
                }
                item {
                    GlassBarButton(
                        icon = Icons.AutoMirrored.Rounded.MenuBook,
                        label = "使用文档",
                        accent = StateAccent,
                        onClick = { openUrl(context, DOC_URL) },
                        glass = liquidGlass,
                        backdrop = backdrop
                    )
                }
                item {
                    GlassBarButton(
                        icon = Icons.Rounded.Groups,
                        label = "一键加入Q群（$QQ_GROUP_ID）",
                        accent = QqBrandBlue,
                        onClick = { showQqGroupDialog = true },
                        glass = liquidGlass,
                        backdrop = backdrop
                    )
                }
            }
        }

    if (showStopDialog) {
        StopServiceDialog(
            onDismiss = { showStopDialog = false },
            onConfirm = {
                SyncHolder.setServerEnabled(false)
                showStopDialog = false
            }
        )
    }
    if (showQqGroupDialog) {
        QqGroupDialog(onDismiss = { showQqGroupDialog = false })
    }
}

// 页脚 tips：同步自 ResonaUI 上游关于页（src/pages/settings/ab/ab.ux Saying），
// 每次进入页面随机一条
private val RESONA_TIPS = listOf(
    "BugMI HyperBUG!",
    "325799 懂得都懂",
    "卫士不语功，唯时代尔",
    "Ciallo～(∠・ω< )⌒☆",
    "由 XiaomiVela 费力驱动"
)

@Composable
private fun ResonaTips() {
    val tip = remember { RESONA_TIPS[kotlin.random.Random.nextInt(RESONA_TIPS.size)] }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            tip,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

// 关闭转码服务确认框：避免误触中断手表正在进行的图片转码任务
@Composable
private fun StopServiceDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            colors = CardColors(
                color = MiuixTheme.colorScheme.surface,
                contentColor = MiuixTheme.colorScheme.onSurface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    "关闭转码服务？",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "关闭后手机将停止监听 HTTP 请求（端口 ${TranscodeServer.PORT}），手表无法创建或查询转码任务，进行中的任务会中断。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "继续运行",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    // 危险操作为红色填充按钮、白色文字（HyperOS 弹窗风格）
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                            color = StateDanger,
                            contentColor = Color.White
                        )
                    ) {
                        Text("确认关闭", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }
            }
        }
    }
}

// 打开外部链接（浏览器 / 自定义 scheme）：无可用应用时 Toast 提示，不崩溃
private fun openUrl(context: Context, url: String): Boolean {
    return try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开：${e.message}", Toast.LENGTH_SHORT).show()
        false
    }
}

// 一键加 QQ 群：弹窗打开时先尝试拉起 QQ 加群卡片；
// 未安装 QQ 或拉起失败时，可直接扫描弹窗内的二维码入群
@Composable
private fun QqGroupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun joinViaQq() {
        val uri = "mqqapi://card/show_pslcard?src_type=internal&version=1" +
            "&uin=$QQ_GROUP_ID&card_type=group&source=qrcode"
        openUrl(context, uri)
    }
    LaunchedEffect(Unit) { joinViaQq() }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            colors = CardColors(
                color = MiuixTheme.colorScheme.surface,
                contentColor = MiuixTheme.colorScheme.onSurface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "加入 QQ 交流群",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "群号：$QQ_GROUP_ID",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(14.dp))
                Image(
                    painter = painterResource(R.drawable.qrcode_1788229962901),
                    contentDescription = "QQ 群二维码",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "未自动打开 QQ？用 QQ 扫描上方二维码即可入群",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { joinViaQq() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                        color = QqBrandBlue,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Rounded.Groups, null, modifier = Modifier.size(18.dp), tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "打开 QQ 加群",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(20.dp))
                TextButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ============================== 设置页 ==============================

// 云端授权弹窗类型：选择授权方式 / 激活进度 / 修改授权
private enum class AuthSheet { CHOOSE, PROGRESS, EDIT }

@Composable
private fun SettingsScreen(
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onOpenSubPage: (SyncSubPage) -> Unit,
    liquidGlass: Boolean,
    backdrop: Backdrop,
    backgroundSelection: String,
    onLiquidGlassChange: (Boolean) -> Unit,
    onReplayOnboarding: () -> Unit
) {
    val context = LocalContext.current

    // 玻璃卡片：只看液态玻璃开关。关闭后所有卡片回到普通实心样式，
    // 自定义背景只负责铺底，不再顺带打开玻璃效果
    val glass = liquidGlass

    var showCache by remember { mutableStateOf(false) }

    // 转码服务相关开关（与 AppPrefs 持久化同步）
    val prefs = AppPrefs.get(context)
    var autoRun by remember { mutableStateOf(prefs.autoRunService) }
    var keepAlive by remember { mutableStateOf(prefs.keepAlive) }
    var notifyReq by remember { mutableStateOf(prefs.notifyTranscode) }
    var sdkBackend by remember { mutableStateOf(prefs.sdkBackend) }
    var autoLaunch by remember { mutableStateOf(prefs.autoLaunchWearApp) }

    // 云端授权（tunnel APIKey）：整卡显示授权状态，点击后按是否已授权进入
    // 「选择授权方式」或「修改授权」弹窗；激活过程在独立进度弹窗内展示。
    var hasCloudKey by remember { mutableStateOf(prefs.cloudApiKey.isNotBlank()) }
    var activating by remember { mutableStateOf(false) }
    var activationSucceeded by remember { mutableStateOf(false) }
    var activationMsg by remember {
        mutableStateOf(if (prefs.cloudApiKey.isNotBlank()) "授权成功，云端隧道可用" else "未授权")
    }
    var authSheet by remember { mutableStateOf<AuthSheet?>(null) }
    DisposableEffect(Unit) {
        val listener = object : ActivationManager.ActivationListener {
            override fun onActivationLog(line: String) {
                activationMsg = line
            }
            override fun onActivationStateChanged(running: Boolean, success: Boolean) {
                activating = running
                if (!running) {
                    hasCloudKey = AppPrefs.get(context).cloudApiKey.isNotBlank()
                    activationSucceeded = success
                    if (success) activationMsg = "授权成功，云端隧道可用"
                }
            }
        }
        ActivationManager.addListener(listener)
        onDispose { ActivationManager.removeListener(listener) }
    }
    fun beginAuth(force: Boolean) {
        activationSucceeded = false
        authSheet = AuthSheet.PROGRESS
        ActivationManager.startActivation(context, force) { }
    }

    // Android 13+ 通知开关开启时需要运行时申请 POST_NOTIFICATIONS。
    // 必须等用户授权后再启动前台服务：未授权时 startForeground 的通知会被系统隐藏，
    // 前台服务也可能被直接终止，授权返回后不会补通知。
    var notifGrantedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val cb = notifGrantedCallback
        notifGrantedCallback = null
        if (granted) cb?.invoke()
    }
    fun hasNotifPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    fun withNotifPermission(onGranted: () -> Unit) {
        if (hasNotifPermission()) {
            onGranted()
        } else {
            notifGrantedCallback = onGranted
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // 请求「不受电池优化限制」。厂商冻结器（MIUI/HyperOS 的 mimd）会冻结普通后台进程，
    // 导致 6732 端口只排队不响应（手表侧 0 KB →「云端下载失败」）。已授权则直接跳过。
    fun requestIgnoreBatteryOptimizations() {
        openBatteryOptimizationSettings(context)
    }
    SubPageScaffold(
        title = "设置",
        onBack = null,
        transparent = liquidGlass || backgroundSelection.isNotBlank()
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
                // 1、深色模式（滑轨式：跟随系统 / 日间 / 夜间）
                item {
                    SettingCard(
                        glass = glass,
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth(),
                        hasBackground = backgroundSelection.isNotBlank()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                // 液态玻璃会锁定夜间主题，此时深色模式不可调，整卡置灰
                                .graphicsLayer { alpha = if (liquidGlass) 0.45f else 1f }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.DarkMode,
                                    contentDescription = null,
                                    tint = if (liquidGlass) {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    } else {
                                        StateAccent
                                    },
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(Modifier.size(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "深色模式",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (liquidGlass) {
                                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        } else {
                                            Color.Unspecified
                                        }
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        if (liquidGlass) {
                                            "液态玻璃已锁定夜间主题，关闭后可调"
                                        } else {
                                            "日间、夜间或跟随系统深浅色"
                                        },
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            SegmentedSlider(
                                options = listOf("跟随系统", "日间", "夜间"),
                                selectedIndex = when (themeMode) {
                                    AppPrefs.MODE_LIGHT -> 1
                                    AppPrefs.MODE_DARK -> 2
                                    else -> 0
                                },
                                enabled = !liquidGlass,
                                glass = glass && GlassTuning.scopeSelector,
                                backdrop = backdrop,
                                onSelected = { index ->
                                    val mode = when (index) {
                                        1 -> AppPrefs.MODE_LIGHT
                                        2 -> AppPrefs.MODE_DARK
                                        else -> AppPrefs.MODE_SYSTEM
                                    }
                                    AppPrefs.get(context).themeMode = mode
                                    onThemeModeChange(mode)
                                }
                            )
                        }
                    }
                }
                // 2、液态玻璃：左半区进入实验室（设置），右半区为开关，中间竖线分隔
                item {
                    val glassSummary = "底栏等开关交互变为液态玻璃，并自动锁定夜间主题；点按左侧可实时调校参数。"
                    SettingCard(
                        glass = glass,
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth(),
                        hasBackground = backgroundSelection.isNotBlank()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 分割线左侧：整块可点，进入液态玻璃实验室
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable { onOpenSubPage(SyncSubPage.LIQUID_GLASS) }
                                    .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WaterDrop,
                                    contentDescription = null,
                                    tint = StateAccent,
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(Modifier.size(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Liquid Glass",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        glassSummary,
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            // 分割线：1dp 竖线，上下留出内边距
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .padding(vertical = 12.dp)
                                    .width(1.dp)
                                    .background(
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            .copy(alpha = 0.25f)
                                    )
                            )
                            // 分割线右侧：仅开关
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .clickable { onLiquidGlassChange(!liquidGlass) }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                GlassSwitch(
                                    checked = liquidGlass,
                                    onCheckedChange = onLiquidGlassChange,
                                    glass = glass,
                                    backdrop = backdrop
                                )
                            }
                        }
                    }
                }
                // 3、背景图：默认灰色或自定义图片，点按进入大图左右滑动切换
                item {
                    SettingCard(
                        glass = glass,
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth(),
                        hasBackground = backgroundSelection.isNotBlank(),
                        onClick = { onOpenSubPage(SyncSubPage.BACKGROUND) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Wallpaper,
                                contentDescription = null,
                                tint = StateAccent,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(Modifier.size(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("背景图", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (backgroundSelection.isBlank()) "默认灰色背景" else "已使用自定义背景",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            BackgroundThumbnail(
                                selection = backgroundSelection,
                                modifier = Modifier.size(width = 32.dp, height = 50.dp)
                            )
                            Spacer(Modifier.size(10.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                // 4、手表互联：SimpleFetch 手机端桥接通道（后台自动运行，表端自带
                // fetch 可直连手机局域网地址时完全用不到，此处仅选 SDK 后端，无开关）
                item {
                    Text(
                        "手表互联",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }
                item {
                    SettingCard(
                        glass = glass,
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth(),
                        hasBackground = backgroundSelection.isNotBlank()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Bluetooth,
                                    contentDescription = null,
                                    tint = StateAccent,
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(Modifier.size(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("互联 SDK", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "SimpleFetch 桥接通道，后台自动连接，无需手动开关",
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            // SimpleFetch 桥接（XMS 消息通道）的 SDK 后端选择；
                            // 通道本身后台自动连接、无需开关，这里只决定走哪个配套服务。
                            SegmentedSlider(
                                options = listOf("AUTO", "小米运动健康", "Oronbox"),
                                selectedIndex = when (sdkBackend) {
                                    AppPrefs.SDK_BACKEND_XIAOMI -> 1
                                    AppPrefs.SDK_BACKEND_ORONBOX -> 2
                                    else -> 0
                                },
                                glass = glass && GlassTuning.scopeSelector,
                                backdrop = backdrop,
                                onSelected = { index ->
                                    val backend = when (index) {
                                        1 -> AppPrefs.SDK_BACKEND_XIAOMI
                                        2 -> AppPrefs.SDK_BACKEND_ORONBOX
                                        else -> AppPrefs.SDK_BACKEND_AUTO
                                    }
                                    sdkBackend = backend
                                    AppPrefs.get(context).sdkBackend = backend
                                    // 净室 SDK 支持运行时切换：setBackend 会自动重绑服务
                                    WearTransport.applyBackendConfig(context)
                                    WearTransport.get(context).ensureConnection()
                                    // 重绑探测（含 3 秒超时回退）完成后再刷新一次渠道显示
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        WearTransport.refreshBackendChannel(context)
                                    }, 4000)
                                    Toast.makeText(context, "已切换互联 SDK", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
                item {
                    SettingSwitchRow(
                        icon = Icons.Rounded.Watch,
                        title = "自动拉起手表快应用",
                        summary = "连接手表时自动打开 BiliMax 快应用；关闭后仅保持通道，不主动拉起",
                        checked = autoLaunch,
                        onCheckedChange = { enable ->
                            autoLaunch = enable
                            AppPrefs.get(context).autoLaunchWearApp = enable
                        },
                        glass = glass,
                        backdrop = backdrop,
                        hasBackground = backgroundSelection.isNotBlank()
                    )
                }
                // 云端授权（tunnel APIKey）：整卡显示是否授权。
                // 未授权→弹窗选择授权方式；已授权→「修改授权」弹窗（编辑/保存/删除 Key）。
                item {
                    val typeLabel = if (prefs.authType == AppPrefs.AUTH_TYPE_NORMAL) "付费" else "免费"
                    SettingRow(
                        icon = Icons.Rounded.Shield,
                        title = "云端授权",
                        summary = "获取 APIKey 后可脱离局域网使用",
                        valueText = if (hasCloudKey) "已授权·$typeLabel" else "未授权",
                        onClick = {
                            authSheet = if (hasCloudKey) AuthSheet.EDIT else AuthSheet.CHOOSE
                        },
                        glass = glass,
                        backdrop = backdrop,
                        hasBackground = backgroundSelection.isNotBlank()
                    )
                }
                // 4、转码服务分组（纯 HTTP 服务器，手机收请求并转码）
                item {
                    Text(
                        "转码服务",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }
                item {
                    SettingSwitchRow(
                        icon = Icons.Rounded.RocketLaunch,
                        title = "自动运行转码服务",
                        summary = "打开应用后自动开启手机端 HTTP 服务器",
                        checked = autoRun,
                        onCheckedChange = { target ->
                            autoRun = target
                            prefs.autoRunService = target
                            if (target && !SyncHolder.isServerRunning()) {
                                SyncHolder.setServerEnabled(true)
                                Toast.makeText(context, "转码服务已开启", Toast.LENGTH_SHORT).show()
                            }
                        },
                        glass = glass,
                        backdrop = backdrop,
                        hasBackground = backgroundSelection.isNotBlank()
                    )
                }
                item {
                    SettingSwitchRow(
                        icon = Icons.Rounded.Bluetooth,
                        title = "后台保活",
                        summary = "以前台服务常驻守护 HTTP 服务器，并申请「不受电池优化限制」以防进程被冻结",
                        checked = keepAlive,
                        onCheckedChange = { target ->
                            keepAlive = target
                            prefs.keepAlive = target
                            if (target) {
                                requestIgnoreBatteryOptimizations()
                                withNotifPermission { SyncHolder.applyKeepAlive(true) }
                            } else {
                                SyncHolder.applyKeepAlive(false)
                            }
                        },
                        glass = glass,
                        backdrop = backdrop,
                        hasBackground = backgroundSelection.isNotBlank()
                    )
                }
                item {
                    SettingSwitchRow(
                        icon = Icons.Rounded.Notifications,
                        title = "收到转码请求时通知",
                        summary = "手表发起转码及任务完成/失败时发送系统通知",
                        checked = notifyReq,
                        onCheckedChange = { target ->
                            notifyReq = target
                            prefs.notifyTranscode = target
                            if (target) withNotifPermission { }
                        },
                        glass = glass,
                        backdrop = backdrop,
                        hasBackground = backgroundSelection.isNotBlank()
                    )
                }
                // 5、其他分组：清除缓存、关于（含开源许可）等杂项入口
                item {
                    Text(
                        "其他",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }
                item {
                    SettingRow(
                        icon = Icons.Rounded.CleaningServices,
                        title = "清除缓存",
                        summary = "转码产物与日志等同步器产生的数据",
                        valueText = formatBytes(scanCacheBytes(context)),
                        onClick = { showCache = true },
                        glass = glass,
                        backdrop = backdrop,
                        hasBackground = backgroundSelection.isNotBlank()
                    )
                }
                // 4、关于：独立子页面（版本信息、开源许可、tips）
                item {
                    SettingRow(
                        icon = Icons.Rounded.Info,
                        title = "关于",
                        summary = "版本号与构建信息",
                        valueText = null,
                        onClick = { onOpenSubPage(SyncSubPage.ABOUT) },
                        glass = glass,
                        backdrop = backdrop,
                        hasBackground = backgroundSelection.isNotBlank()
                    )
                }
                item {
                    SettingRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "重新观看引导",
                        summary = "再次查看首次启动的欢迎与配置页",
                        valueText = null,
                        onClick = {
                            AppPrefs.get(context).onboarded = false
                            onReplayOnboarding()
                        },
                        glass = glass,
                        backdrop = backdrop,
                        hasBackground = backgroundSelection.isNotBlank()
                    )
                }
            }

        // 弹窗必须声明在 Scaffold content 内：OverlayDialog 依赖 Scaffold 挂载的
        // MiuixPopupHost，放在 Scaffold 外时找不到宿主，会表现为点击无反应
        // 清除缓存明细
        if (showCache) {
            CacheDialog(
                onDismiss = { showCache = false }
            )
        }
        // 云端授权：选择方式 / 激活进度 / 修改授权
        when (authSheet) {
            AuthSheet.CHOOSE -> AuthChooseDialog(
                onDismiss = { authSheet = null },
                onPick = { type ->
                    AppPrefs.get(context).authType = type
                    beginAuth(true)
                }
            )
            AuthSheet.PROGRESS -> ActivationProgressDialog(
                running = activating,
                success = activationSucceeded,
                message = activationMsg,
                onConfirm = { authSheet = null },
                onCancel = {
                    ActivationManager.cancelActivation()
                    authSheet = null
                },
                onRetry = { beginAuth(true) }
            )
            AuthSheet.EDIT -> EditAuthDialog(
                currentKey = AppPrefs.get(context).cloudApiKey,
                isFree = AppPrefs.get(context).authType != AppPrefs.AUTH_TYPE_NORMAL,
                onDismiss = { authSheet = null },
                onSave = { newKey ->
                    val p = AppPrefs.get(context)
                    p.cloudApiKey = newKey.trim()
                    hasCloudKey = true
                    Thread {
                        runCatching {
                            SyncHolder.onCloudKeyReady()
                            SfBridge.broadcastServerInfo()
                        }
                    }.apply { isDaemon = true }.start()
                    authSheet = null
                    Toast.makeText(context, "APIKey 已保存", Toast.LENGTH_SHORT).show()
                },
                onDelete = {
                    val p = AppPrefs.get(context)
                    p.cloudApiKey = ""
                    hasCloudKey = false
                    CloudRelayClient.restart()
                    Thread {
                        runCatching { SfBridge.broadcastServerInfo() }
                    }.apply { isDaemon = true }.start()
                    authSheet = null
                    Toast.makeText(context, "APIKey 已删除", Toast.LENGTH_SHORT).show()
                },
                onConvertToPaid = {
                    AppPrefs.get(context).authType = AppPrefs.AUTH_TYPE_NORMAL
                    beginAuth(true)
                }
            )
            null -> {}
        }
    }
}

// 关于：独立子页面。顶部居中应用图标，名称与版本在其正下方；
// 连点图标 5 次自动导出崩溃日志并触发系统分享；
// 连点设备型号 3 次再连点系统版本 2 次可唤出鲸鱼彩蛋；
// 开放源代码许可入口与页脚 tips 也收拢于此
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    var showWhaleEgg by remember { mutableStateOf(false) }
    // 图标连点计数：点满 5 次导出最近一次崩溃日志并拉起系统分享；点满即重置
    var iconTapCount by remember { mutableStateOf(0) }
    // 彩蛋触发进度：设备型号点 3 下（0→3），再系统版本点 2 下（3→5）
    var eggStage by remember { mutableStateOf(0) }

    val appName = remember {
        runCatching {
            context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        }.getOrDefault("BiliMax 助手")
    }
    val info = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
    }
    val versionName = info?.versionName ?: "未知"
    val versionCode = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode?.toInt() ?: 0 else 0
    val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val buildType = if (isDebug) "Debug 调试构建" else "Release 发布构建"

    SubPageScaffold(title = "关于", onBack = onBack, transparent = true) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 应用图标 + 名称 + 版本：整体居中；仅图标连点 5 次导出崩溃日志
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                iconTapCount++
                                if (iconTapCount >= 5) {
                                    iconTapCount = 0
                                    val report = CrashReporter.latestReport(context)
                                    if (report != null) {
                                        shareCrashLog(context, report)
                                    } else {
                                        Toast.makeText(context, "暂无崩溃日志", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        appName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "版本 $versionName ($versionCode)",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "手表快应用的转码后端与互联通道",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            item {
                SubPageCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        AboutLine("构建类型", buildType)
                        AboutLine("构建时间", BuildConfig.BUILD_TIME)
                        AboutLine("构建者", BuildConfig.BUILDER_NAME)
                    }
                }
            }
            item {
                SubPageCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        AboutLine(
                            "设备型号",
                            "${Build.MANUFACTURER} ${Build.MODEL}",
                            onClick = {
                                // 第一阶段：连点设备型号 3 下（0→3）；已进入第二阶段后再点不影响
                                if (eggStage < 3) eggStage++
                            }
                        )
                        AboutLine(
                            "系统版本",
                            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                            onClick = {
                                // 第二阶段：设备型号点满 3 下后，连点系统版本 2 下（3→5）
                                if (eggStage in 3..4) {
                                    eggStage++
                                    if (eggStage >= 5) {
                                        eggStage = 0
                                        showWhaleEgg = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
            // 制作团队：开发团队与测试团队，头像 + 人名
            item { TeamCreditsCard() }
            item {
                SettingRow(
                    icon = Icons.Rounded.Language,
                    title = "访问官网",
                    summary = "https://wear.b4qaq.cn/",
                    valueText = null,
                    onClick = { openUrl(context, "https://wear.b4qaq.cn/") },
                )
            }
            item {
                SettingRow(
                    icon = Icons.Rounded.AccountBalance,
                    title = "开放源代码许可",
                    summary = "查看同步器使用的开源项目及其协议",
                    valueText = null,
                    onClick = { showLicenses = true }
                )
            }
            item { ResonaTips() }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "© 2026 MingchengNetworkStudio 保留所有权利",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        OpenSourceLicensesDialog(
            show = showLicenses,
            onDismiss = { showLicenses = false }
        )
        WhaleEggDialog(
            show = showWhaleEgg,
            onDismiss = { showWhaleEgg = false }
        )
    }
}

// 通用设置行：左图标 + 标题/描述，右侧为模式名/大小或进入箭头
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    summary: String,
    valueText: String?,
    onClick: () -> Unit,
    glass: Boolean = false,
    backdrop: Backdrop? = null,
    hasBackground: Boolean = false
) {
    if (glass && backdrop != null && GlassTuning.scopeCard) {
        SettingCard(
            glass = true,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            hasBackground = hasBackground,
            onClick = onClick
        ) {
            SettingRowContent(icon, title, summary, valueText)
        }
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        colors = CardColors(
            // 与首页卡片一致：自定义背景下半透明，文字仍清晰
            color = if (hasBackground) {
                lerp(MiuixTheme.colorScheme.surface, StateAccent, 0.12f).copy(alpha = 0.75f)
            } else {
                MiuixTheme.colorScheme.surface
            },
            contentColor = MiuixTheme.colorScheme.onSurface
        ),
        onClick = onClick
    ) {
        SettingRowContent(icon, title, summary, valueText)
    }
}

@Composable
private fun SettingRowContent(
    icon: ImageVector,
    title: String,
    summary: String,
    valueText: String?
) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = StateAccent,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            if (valueText != null) {
                Text(
                    valueText,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.size(8.dp))
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(22.dp)
            )
        }
}

// 通用开关设置行：左图标 + 标题/描述，右侧 Miuix 开关；整行点击等同切换
@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    glass: Boolean = false,
    backdrop: Backdrop,
    hasBackground: Boolean = false
) {
    SettingCard(
        glass = glass,
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth(),
        hasBackground = hasBackground,
        onClick = { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = StateAccent,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            GlassSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                glass = glass,
                backdrop = backdrop
            )
        }
    }
}

// 设置页卡片容器：玻璃开启时与首页卡片一致（背景折射 + 细描边 + 极淡强调色），
// 未开启时回退 miuix 实心卡片。
@Composable
private fun SettingCard(
    glass: Boolean,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    // 自定义背景时用与首页一致的半透明底色
    hasBackground: Boolean = false,
    // 卡片圆角与强调色：子页面卡片会用到 14dp 与语义色（成功/警告等）
    cornerRadius: Dp = 18.dp,
    accent: Color = StateAccent,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (glass && GlassTuning.scopeCard) {
        val shape = RoundedRectangle(cornerRadius)
        val interaction = remember { MutableInteractionSource() }
        val btn = GlassTuning.button
        Box(
            modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        if (btn.vibrancy) vibrancy()
                        if (btn.blurDp > 0f) blur(btn.blurDp.dp.toPx())
                        lens(
                            btn.lensHeightDp.dp.toPx(),
                            btn.lensAmountDp.dp.toPx(),
                            btn.depth,
                            btn.chromatic
                        )
                    },
                    highlight = {
                        if (btn.highlight) Highlight.Default else Highlight.Plain.copy(alpha = 0f)
                    },
                    innerShadow = {
                        if (btn.innerShadow) CardInnerShadow else CardInnerShadow.copy(alpha = 0f)
                    },
                    onDrawSurface = { drawRect(accent.copy(alpha = btn.tintAlpha)) }
                )
                .border(BorderStroke(1.dp, accent.copy(alpha = 0.24f)), shape)
                .then(
                    if (onClick == null) {
                        Modifier
                    } else {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick
                        )
                    }
                )
        ) {
            content()
        }
        return
    }
    if (onClick != null) {
        Card(
            modifier = modifier,
            cornerRadius = cornerRadius,
            pressFeedbackType = PressFeedbackType.Sink,
            showIndication = true,
            colors = CardColors(
                // 与首页卡片一致：自定义背景下半透明，文字仍清晰
                color = if (hasBackground) {
                    lerp(MiuixTheme.colorScheme.surface, StateAccent, 0.12f).copy(alpha = 0.75f)
                } else {
                    MiuixTheme.colorScheme.surface
                },
                contentColor = MiuixTheme.colorScheme.onSurface
            ),
            onClick = onClick
        ) {
            content()
        }
    } else {
        Card(
            modifier = modifier,
            cornerRadius = cornerRadius,
            colors = CardColors(
                color = if (hasBackground) {
                    lerp(MiuixTheme.colorScheme.surface, StateAccent, 0.12f).copy(alpha = 0.75f)
                } else {
                    MiuixTheme.colorScheme.surface
                },
                contentColor = MiuixTheme.colorScheme.onSurface
            )
        ) {
            content()
        }
    }
}

// 子页面玻璃开关：由 SubPageContent 下发，子页面内的卡片据此决定是否画玻璃
private val LocalSubPageGlass = compositionLocalOf { false }
private val LocalSubPageBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * 子页面通用卡片（转码 / 传输 / 历史 / 日志 / 关于）：
 * 开启液态玻璃且「生效范围 · 卡片」打开时与设置页卡片同款玻璃；
 * 否则回退到调用方给定的 miuix 实心底色。
 */
@Composable
private fun SubPageCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    accent: Color = StateAccent,
    pressFeedbackType: PressFeedbackType = PressFeedbackType.Sink,
    showIndication: Boolean = true,
    colors: CardColors = CardColors(
        color = MiuixTheme.colorScheme.surface,
        contentColor = MiuixTheme.colorScheme.onSurface
    ),
    content: @Composable () -> Unit
) {
    val glass = LocalSubPageGlass.current
    val backdrop = LocalSubPageBackdrop.current
    if (glass && backdrop != null && GlassTuning.scopeCard) {
        SettingCard(
            glass = true,
            backdrop = backdrop,
            modifier = modifier,
            cornerRadius = cornerRadius,
            accent = accent,
            onClick = onClick,
            content = content
        )
        return
    }
    if (onClick != null) {
        Card(
            modifier = modifier,
            cornerRadius = cornerRadius,
            pressFeedbackType = pressFeedbackType,
            showIndication = showIndication,
            colors = colors,
            onClick = onClick
        ) {
            content()
        }
    } else {
        Card(modifier = modifier, cornerRadius = cornerRadius, colors = colors) {
            content()
        }
    }
}

@Composable
private fun GlassSurface(
    accent: Color,
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedRectangle(18.dp),
    pressedScale: Float = 1.06f,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val btn = GlassTuning.button
    val surfaceColor = accent.copy(alpha = btn.tintAlpha)
    val borderColor = accent.copy(alpha = 0.24f)
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = if (btn.bouncy) Spring.DampingRatioMediumBouncy else 1f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "glassPressScale"
    )
    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    if (btn.vibrancy) vibrancy()
                    if (btn.blurDp > 0f) blur(btn.blurDp.dp.toPx())
                    lens(
                        btn.lensHeightDp.dp.toPx(),
                        btn.lensAmountDp.dp.toPx(),
                        btn.depth,
                        btn.chromatic
                    )
                },
                highlight = {
                    if (btn.highlight) Highlight.Default else Highlight.Plain.copy(alpha = 0f)
                },
                innerShadow = {
                    if (btn.innerShadow) CardInnerShadow else CardInnerShadow.copy(alpha = 0f)
                },
                layerBlock = {
                    scaleX = scale
                    scaleY = scale
                },
                onDrawSurface = { drawRect(surfaceColor) }
            )
            .border(BorderStroke(1.dp, borderColor), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        content()
    }
}

// 切换按钮：miuix Switch 外层叠加液态玻璃折射。开启时轨道为半透明强调色玻璃，
// 关闭时为中性玻璃；关闭玻璃开关则回退 miuix 默认实心开关。
@Composable
private fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    glass: Boolean,
    backdrop: Backdrop
) {
    if (!glass) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = true,
            colors = SwitchDefaults.switchColors(checkedTrackColor = StateAccent)
        )
        return
    }
    val shape = Capsule()
    // 参考 iOS Liquid Glass：轨道本身带颜色——未选中浅灰、选中浅蓝（强调色），
    // 玻璃感来自顶部白色高光、透镜折射边缘与同色描边，而不是把轨道做透明。
    // tint 用 animateColorAsState 随开关状态平滑过渡。
    val targetTint = if (checked) {
        StateAccent.copy(alpha = 0.85f)
    } else {
        Color(0xFF787880).copy(alpha = 0.22f)
    }
    val trackTint by animateColorAsState(targetValue = targetTint, label = "trackTint")
    val borderTint by animateColorAsState(
        targetValue = if (checked) StateAccent.copy(alpha = 0.90f) else Color(0xFF787880).copy(alpha = 0.40f),
        label = "borderTint"
    )
    Box {
        val btn = GlassTuning.button
        Box(
            Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        if (btn.vibrancy) vibrancy()
                        if (btn.blurDp > 0f) blur(btn.blurDp.dp.toPx())
                        lens(
                            btn.lensHeightDp.dp.toPx() * 0.55f,
                            btn.lensAmountDp.dp.toPx(),
                            btn.depth,
                            btn.chromatic
                        )
                    },
                    highlight = {
                        if (btn.highlight) Highlight.Default else Highlight.Plain.copy(alpha = 0f)
                    },
                    onDrawSurface = { drawRect(trackTint) }
                )
                .border(1.dp, borderTint, shape)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = true,
            colors = SwitchDefaults.switchColors(
                checkedThumbColor = Color.White,
                uncheckedThumbColor = Color.White,
                checkedTrackColor = Color.Transparent,
                uncheckedTrackColor = Color.Transparent
            )
        )
    }
}

// 滑轨式分段选择器：圆角轨道 + 滑块随选中项平滑移动，选中项文字深色反白
@Composable
private fun SegmentedSlider(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glass: Boolean = false,
    backdrop: Backdrop? = null,
    // 深色玻璃页需要显式指定文字色，其余调用点保持主题默认
    selectedTextColor: Color = Color.White,
    unselectedTextColor: Color? = null
) {
    val count = options.size.coerceAtLeast(1)
    val safeIndex = selectedIndex.coerceIn(0, count - 1)
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val neutralColor = if (isDark) Color.White else Color.Black
    // 玻璃参数：轨道取“导轨”组，选中块取“滑块”组
    val trackSpec = GlassTuning.track
    val thumbSpec = GlassTuning.thumb
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(Capsule())
            .then(
                if (glass && backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            if (trackSpec.vibrancy) vibrancy()
                            if (trackSpec.blurDp > 0f) blur(trackSpec.blurDp.dp.toPx())
                            lens(
                                trackSpec.lensHeightDp.dp.toPx(),
                                trackSpec.lensAmountDp.dp.toPx(),
                                trackSpec.depth,
                                trackSpec.chromatic
                            )
                        },
                        highlight = {
                            if (trackSpec.highlight) Highlight.Default else Highlight.Plain.copy(alpha = 0f)
                        },
                        innerShadow = {
                            if (trackSpec.innerShadow) {
                                InnerShadow.Default
                            } else {
                                InnerShadow.Default.copy(alpha = 0f)
                            }
                        },
                        onDrawSurface = { drawRect(neutralColor.copy(alpha = trackSpec.tintAlpha)) }
                    )
                } else {
                    Modifier.background(MiuixTheme.colorScheme.surfaceContainer)
                }
            )
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
    ) {
        val itemWidth = maxWidth / count
        val animatedOffset by animateDpAsState(
            targetValue = itemWidth * safeIndex,
            label = "thumbOffset"
        )
        // 滑块：轨道内边距 4dp，宽为一项减两侧间隙，随选中项平滑滑动
        Box(
            modifier = Modifier
                .offset(x = animatedOffset + 4.dp, y = 4.dp)
                .width(itemWidth - 8.dp)
                .height(32.dp)
                .clip(Capsule())
                .then(
                    if (glass && backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                if (thumbSpec.vibrancy) vibrancy()
                                if (thumbSpec.blurDp > 0f) blur(thumbSpec.blurDp.dp.toPx())
                                lens(
                                    refractionHeight = thumbSpec.lensHeightDp.dp.toPx(),
                                    refractionAmount = thumbSpec.lensAmountDp.dp.toPx(),
                                    depthEffect = thumbSpec.depth,
                                    chromaticAberration = thumbSpec.chromatic
                                )
                            },
                            highlight = {
                                if (thumbSpec.highlight) {
                                    Highlight.Default
                                } else {
                                    Highlight.Plain.copy(alpha = 0f)
                                }
                            },
                            onDrawSurface = {
                                drawRect(neutralColor.copy(alpha = thumbSpec.tintAlpha))
                            }
                        )
                    } else {
                        Modifier.background(StateAccent)
                    }
                )
        )
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                val selected = index == safeIndex
                val textColor by animateColorAsState(
                    targetValue = if (selected) {
                        selectedTextColor
                    } else {
                        unselectedTextColor ?: MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    label = "segText"
                )
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .fillMaxSize()
                        .clip(Capsule())
                        .then(
                            if (enabled) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onSelected(index) }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = textColor
                    )
                }
            }
        }
    }
}

// 缓存分类
private data class CacheItem(
    val key: String,
    val title: String,
    val detail: String,
    val bytes: Long,
    val enabled: Boolean,
    val disabledReason: String?
)

// 扫描同步器产生的全部数据：转码产物 + 日志
private fun scanCacheItems(context: Context): List<CacheItem> {
    val serviceRunning = SyncHolder.isServerRunning()
    // 转码产物：服务任务目录（运行中可能正在写，禁删）+ 手动转码工作目录（导出后残留）
    val srvJobs = File(context.cacheDir, "srv_jobs")
    val manualJobs = File(context.cacheDir, "transcode")
    // 手动传输的托管副本与临时目录：此前既不统计也不清理，「清除缓存」触达不到
    val srvPush = File(context.cacheDir, "srv_push")
    val pushWork = File(context.cacheDir, "push_work")
    val pushPick = File(context.cacheDir, "push_pick")
    val transcodeBytes = dirSize(srvJobs) + dirSize(manualJobs) +
        dirSize(srvPush) + dirSize(pushWork) + dirSize(pushPick)
    // 日志：崩溃报告 + 实时 logcat + 外部目录崩溃日志 + 分享临时文件
    val reportsDir = File(context.filesDir, CrashReporterDir)
    val logsBytes = reportsDir.listFiles()
        ?.filter { it.name.startsWith(CrashReporterPrefix) || it.name == CrashReporterLiveLog }
        ?.sumOf { if (it.isFile) it.length() else dirSize(it) }
        ?: 0L
    val extLogs = context.getExternalFilesDir(CrashReporterExtDir)
    val extBytes = if (extLogs != null) {
        extLogs.listFiles()?.filter { it.name.startsWith(CrashReporterPrefix) }
            ?.sumOf { it.length() } ?: 0L
    } else 0L
    val sharedLogs = dirSize(File(context.cacheDir, "shared_logs"))
    // 服务器处理 BVID 任务时下载的源视频（cacheDir/bili_video）；转码完成后即可删除
    val videoDir = File(context.cacheDir, "bili_video")
    val videoBytes = dirSize(videoDir)
    // 转码历史：仅 history.json 一个小文件，显示记录条数
    val historyCount = HistoryStore.all(context).size
    val historyFile = File(context.filesDir, "history.json")
    return listOf(
        CacheItem(
            key = "history",
            title = "转码历史",
            detail = "手动转码、手表请求转码与手动传输的记录，共 $historyCount 条",
            bytes = if (historyFile.exists()) historyFile.length() else 0L,
            enabled = historyCount > 0,
            disabledReason = if (historyCount == 0) "暂无历史记录" else null
        ),
        CacheItem(
            key = "transcode",
            title = "转码产物",
            detail = "服务转码任务与手动转码的胶卷、音频、临时输入文件",
            bytes = transcodeBytes,
            enabled = !serviceRunning,
            disabledReason = if (serviceRunning) "转码服务运行中，停止后才可清理" else null
        ),
        CacheItem(
            key = "videos",
            title = "下载的视频",
            detail = "手表请求 B 站视频转码时下载的源视频，生成胶卷后即可清理",
            bytes = videoBytes,
            enabled = true,
            disabledReason = null
        ),
        CacheItem(
            key = "logs",
            title = "日志与崩溃记录",
            detail = "崩溃报告、实时运行日志及分享用临时文件",
            bytes = logsBytes + extBytes + sharedLogs,
            enabled = true,
            disabledReason = null
        )
    )
}

private fun scanCacheBytes(context: Context): Long =
    scanCacheItems(context).sumOf { it.bytes }

// 删除勾选的分类；实时日志用截断而非删除（logcat 线程持有文件句柄持续追加）
private fun clearCache(context: Context, keys: Set<String>): Long {
    var freed = 0L
    if ("history" in keys) {
        val historyFile = File(context.filesDir, "history.json")
        if (historyFile.exists()) freed += historyFile.length()
        HistoryStore.clear(context)
    }
    if ("transcode" in keys) {
        val srvJobs = File(context.cacheDir, "srv_jobs")
        val manualJobs = File(context.cacheDir, "transcode")
        val srvPush = File(context.cacheDir, "srv_push")
        val pushWork = File(context.cacheDir, "push_work")
        val pushPick = File(context.cacheDir, "push_pick")
        freed += dirSize(srvJobs) + dirSize(manualJobs) +
            dirSize(srvPush) + dirSize(pushWork) + dirSize(pushPick)
        srvJobs.deleteRecursively()
        manualJobs.deleteRecursively()
        srvPush.deleteRecursively()
        pushWork.deleteRecursively()
        pushPick.deleteRecursively()
    }
    if ("videos" in keys) {
        val videoDir = File(context.cacheDir, "bili_video")
        freed += dirSize(videoDir)
        videoDir.deleteRecursively()
    }
    if ("logs" in keys) {
        val reportsDir = File(context.filesDir, CrashReporterDir)
        reportsDir.listFiles()?.forEach { f ->
            if (f.name.startsWith(CrashReporterPrefix)) {
                freed += if (f.isFile) f.length() else dirSize(f)
                f.deleteRecursively()
            } else if (f.name == CrashReporterLiveLog && f.isFile) {
                freed += f.length()
                runCatching { f.writeText("") }
            }
        }
        context.getExternalFilesDir(CrashReporterExtDir)?.listFiles()?.forEach { f ->
            if (f.name.startsWith(CrashReporterPrefix)) {
                freed += f.length()
                f.delete()
            }
        }
        val shared = File(context.cacheDir, "shared_logs")
        freed += dirSize(shared)
        shared.deleteRecursively()
        runCatching { CrashReporter.clear(context) }
    }
    return freed
}

private const val CrashReporterDir = "crash_reports"
private const val CrashReporterExtDir = "crash_logs"
private const val CrashReporterPrefix = "crash_"
private const val CrashReporterLiveLog = "live_logcat.txt"

private fun dirSize(dir: File): Long {
    if (!dir.exists()) return 0L
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

// 选择授权方式弹窗：免费授权 / 付费授权二选一，选定后写入 authType 并开始激活
@Composable
private fun AuthChooseDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            colors = CardColors(
                color = MiuixTheme.colorScheme.surface,
                contentColor = MiuixTheme.colorScheme.onSurface
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("云端授权", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "选择授权方式以获取 APIKey",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onPick(AppPrefs.AUTH_TYPE_FREE) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("免费授权", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onPick(AppPrefs.AUTH_TYPE_NORMAL) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("付费授权", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// 授权进度弹窗：不确定进度条 +「授权进度」标题 + 实时日志；
// 成功后显示确认按钮返回，失败可重试，进行中可取消。
@Composable
private fun ActivationProgressDialog(
    running: Boolean,
    success: Boolean,
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    Dialog(onDismissRequest = { }) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            colors = CardColors(
                color = MiuixTheme.colorScheme.surface,
                contentColor = MiuixTheme.colorScheme.onSurface
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    if (success) "授权成功" else "授权进度",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = if (running) null else if (success) 1f else 0f,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(18.dp))
                when {
                    success -> TextButton(
                        text = "确认",
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth()
                    )
                    running -> TextButton(
                        text = "取消",
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                            textColor = StateDanger
                        )
                    )
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            text = "取消",
                            onClick = onCancel,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "重试",
                            onClick = onRetry,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

// 修改授权弹窗：密钥输入框可查看/编辑，支持保存或删除；
// 免费用户额外提供「转为付费用户」按钮。
@Composable
private fun EditAuthDialog(
    currentKey: String,
    isFree: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onConvertToPaid: () -> Unit
) {
    var input by remember { mutableStateOf(currentKey) }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            colors = CardColors(
                color = MiuixTheme.colorScheme.surface,
                contentColor = MiuixTheme.colorScheme.onSurface
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("修改授权", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "可直接编辑 APIKey，保存后立即生效",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(14.dp))
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    label = "APIKey",
                    useLabelAsPlaceholder = true,
                    singleLine = false
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onSave(input) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = input.trim().isNotBlank(),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                        color = StateAccent,
                        contentColor = Color.White
                    )
                ) {
                    Text("保存密钥", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                        color = StateDanger,
                        contentColor = Color.White
                    )
                ) {
                    Text("删除密钥", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
                if (isFree) {
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        text = "转为付费用户",
                        onClick = onConvertToPaid,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digit = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / 1024.0.pow(digit)
    return if (digit == 0) "${bytes} B" else String.format("%.1f %s", value, units[digit])
}

// 清除缓存明细弹窗：分类勾选后删除
@Composable
private fun CacheDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(scanCacheItems(context)) }
    val checked = remember { mutableStateListOf<String>().apply { addAll(items.filter { it.enabled }.map { it.key }) } }

    WindowDialog(
        show = true,
        title = "清除缓存",
        summary = "同步器产生的转码产物与日志都在本应用专属目录中，勾选后删除",
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.fillMaxWidth()) {
            items.forEach { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    colors = if (item.enabled) {
                        CardColors(
                            MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                            MiuixTheme.colorScheme.onSurface
                        )
                    } else {
                        CardColors(
                            StateWarning.copy(alpha = 0.10f),
                            MiuixTheme.colorScheme.onSurface
                        )
                    },
                    pressFeedbackType = if (item.enabled) PressFeedbackType.Sink else PressFeedbackType.None,
                    showIndication = item.enabled,
                    onClick = {
                        if (!item.enabled) return@Card
                        if (item.key in checked) checked.remove(item.key) else checked.add(item.key)
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            state = if (item.key in checked) {
                                androidx.compose.ui.state.ToggleableState.On
                            } else {
                                androidx.compose.ui.state.ToggleableState.Off
                            },
                            onClick = if (item.enabled) {
                                {
                                    if (item.key in checked) checked.remove(item.key) else checked.add(item.key)
                                }
                            } else null,
                            enabled = item.enabled
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (item.enabled) {
                                        MiuixTheme.colorScheme.onSurface
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    }
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    formatBytes(item.bytes),
                                    fontSize = 13.sp,
                                    color = StateAccent
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                item.disabledReason ?: item.detail,
                                fontSize = 12.sp,
                                color = if (item.disabledReason != null) {
                                    StateWarning
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "已选可释放 ${formatBytes(items.filter { it.key in checked }.sumOf { it.bytes })}",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val freed = clearCache(context, checked.toSet())
                        items = scanCacheItems(context)
                        checked.clear()
                        checked.addAll(items.filter { it.enabled }.map { it.key })
                        Toast.makeText(context, "已释放 ${formatBytes(freed)}", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    enabled = checked.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("删除所选", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun AboutLine(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = onClick != null,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { onClick?.invoke() }
            )
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// 制作团队卡片：开发团队 / 测试团队，每位成员为「头像 + 人名」
private data class TeamMember(val name: String, val avatarRes: Int)

// 彩蛋：关于页连点 B4QAQ 成员行，名字按此顺序循环切换
private val B4QAQ_EGG_NAMES = listOf(
    "B4QAQ",
    "B4AWA",
    "B4OVO",
    "B4U_U",
    "B4O.O",
    "B4^o^",
    "B4X_X",
    "B4T_T",
    "B4O_O",
    "B4> <",
    "B4-_-"
)

@Composable
private fun TeamCreditsCard() {
    SubPageCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                "制作团队",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            TeamGroup(
                title = "开发团队",
                members = listOf(
                    TeamMember("B4QAQ", R.drawable.avatar_b4qaq),
                    TeamMember("fwzjszd", R.drawable.avatar_fwzjszd),
                    TeamMember("缄雨默", R.drawable.avatar_jianyumo)
                )
            )
            Spacer(Modifier.height(12.dp))
            TeamGroup(
                title = "测试团队",
                members = listOf(
                    TeamMember("愿为星萤照夜行", R.drawable.avatar_ywxyzyx),
                    TeamMember("小周", R.drawable.avatar_xiaozhou)
                )
            )
        }
    }
}

@Composable
private fun TeamGroup(title: String, members: List<TeamMember>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            members.forEach { member ->
                val isEgg = member.name == "B4QAQ"
                var eggIndex by remember { mutableStateOf(0) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isEgg) Modifier.clickable { eggIndex = (eggIndex + 1) % B4QAQ_EGG_NAMES.size } else Modifier),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(member.avatarRes),
                        contentDescription = member.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        if (isEgg) B4QAQ_EGG_NAMES[eggIndex] else member.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// 彩蛋鲸鱼原图（文本版）：关于页连点「应用版本」3 下 → 连点「系统版本」2 下 → 再点「关于」入口触发；
// 分享时写入 txt 文件经 FileProvider 以文件流分享
private val WHALE_EGG_ASCII =
    """


                                                                                  ?%#${'$'}#%7&%%#?
                                                                             *&&8@${'$'}%@${'$'}@&${'$'}%&#&#${'$'}%${'$'}
                                                                          o&%8%${'$'}#%7*~-.:~?#%#${'$'}#${'$'}%&v
                                                                        &${'$'}B${'$'}&:                ?@B&8&
                                                                      #8&${'$'}:                     =B%@
                                                                    &88x                         f88+   !:
                                                                  ~@&f                       !vxf-&8x7vx!?7#x,:   ;v*fxoof
                                                                 7B#                      /#${'$'}*   7@${'$'};     !?%o=#@#@       &#${'$'}:
                                                                *8o                       %|              :!    ~|!          ${'$'}7
                                                                Wx               x#vvo${'$'}f7x7=          +|; !      ~     ,      fo#~
                                                               8${'$'}              %7~    -/:   !  !;    /|- +|+   =/*    ,~      |o&*
                                                              ?8${'$'}            -8-       ~/   *?* |*o/*=o==x=/x|7oxo; /| ,     !+-f${'$'}${'$'}! :7=
                                                              %@%         o7?v7        /?++??x=*o&${'$'}${'$'}&@#%#&7%7&*- ;/+/=+/  /=/     v&&x,?v##
                                                              x@8|      v%! |//|   ;/!=xvx7${'$'}W8B@WB8&@&${'$'}%${'$'}%${'$'}%${'$'}%${'$'}&8${'$'}@%7  |?xx/=     -~      ;${'$'}7
                                                               #&@#%o| &f      ?/?+!/f&@&${'$'}%${'$'}%${'$'}%${'$'}%${'$'}${'$'}B@8@8${'$'}8${'$'}${'$'}#&#&#&#&#@&${'$'}= :?+|=+!+~         #f
                                                                 .~    &    -  =*=7v@${'$'}${'$'}%${'$'}${'$'}W8B@@&${'$'}%8&${'$'}%${'$'}&@${'$'}B8B@@%${'$'}%${'$'}%${'$'}%${'$'}&${'$'}&@#- o=+?   ,;,     @@:
                                                                     #7~   !/+!*o@&@%&%8@B${'$'}8&${'$'}%${'$'}%${'$'}%&%${'$'}#%%&#&&@${'$'}8${'$'}&#&%&#&%&#${'$'}%${'$'}v-~v==|--     /${'$'}%@${'$'}=
                                                                  &%#%&!|!  |?f@W${'$'}${'$'}&${'$'}${'$'}W@8&@&&&${'$'}#&#${'$'}%${'$'}&@&${'$'}%${'$'}%${'$'}%${'$'}%@@8&${'$'}%${'$'}%${'$'}%&7&%${'$'}@@~~?=:;;/:;!v&8&${'$'}&@:
                                                                 @/   -+/==x?@8@#&#&@B${'$'}&#&%&#&#&7%#&#&%${'$'}%&#&#%#&#%#@&&#&#&#7f%#&#@%;|f*?/    !&@&${'$'}#8#
                                                                ${'$'}       =/ffB%@&${'$'}%${'$'}8B&${'$'}%${'$'}%${'$'}%${'$'}%${'$'}%&#${'$'}%&%${'$'}%${'$'}&${'$'}%&#${'$'}%&%${'$'}%&%8#%77f7f%#&%${'$'}${'$'}@~??|:--|-|  &8%&${'$'}|
                                                               @~   ;/!|~f#8#&#&%&@@#&#&&&#&%&#&#&#%#&#&#&%&#&#&#&#%#%7@&7vvf%#&#&#&#8o;oo++      &&%&${'$'}*
                                                              ;${'$'}    ;|==v&8%${'$'}%${'$'}%${'$'}@@%${'$'}%${'$'}&${'$'}%&%@%&%${'$'}%&%${'$'}%${'$'}#${'$'}%&${'$'}${'$'}#%#${'$'}%%f#%%#8@%7&%${'$'}%${'$'}%&%${'$'}%8${'$'} o?~!~:    @@${'$'}&@!
                                                              :${'$'}+/; .-|x${'$'}%#&v%#&&${'$'}%&#%#${'$'}#%#&#&#&#%#&77#&x7f&&%77x7xvxvf##&${'$'}&7%#&#&#&#&#&${'$'}?+?;      B%&%&${'$'}|
                                                               !@+*+x*7@#%${'$'}7#%&%@%${'$'}%&#${'$'}&&#&%&7%#&##x77&&#x%#88@%&7&#&%&#&#${'$'}@@#${'$'}%&%${'$'}%&%${'$'}%${'$'}@&+o/?+/*B@@%@&&8;
                                                              o%   //v${'$'}7%${'$'}##f%7@#%7&#%&&#7x%v7xfxfofx%7&#%#%#M@${'$'}#&#&#&#%#&#&${'$'}@#&#&#&%&#&%&&@xx/+/?@@%${'$'}%${'$'}&%@
                                                              ${'$'}?  //f@#%@%${'$'}%#v@${'$'}&#v#778%&f7v${'$'}7vxvfvf&&${'$'}%${'$'}%&%${'$'}@&${'$'}8&${'$'}%${'$'}%&#${'$'}%&%@@8%${'$'}%${'$'}%${'$'}%${'$'}%${'$'}&${'$'}88x+-   @@${'$'}@&8${'$'}8&  ;:
                                                              #%!*=?@77&#&#&7#&&#7xfo%@&##7#7vv7xf7%7&%&7%#&7%&**&#%7%7%7%#%#&${'$'}@#%#&#%#&#&%${'$'}@%o*|/-B&&&${'$'}&@&B7&f8o
                                                             v${'$'} ;/*8#v${'$'}&${'$'}%${'$'}%&%@%%#&###W&&#${'$'}%&#&%&%${'$'}%&%@%&#${'$'}%&#@o|=x%%#&#%7&#%#@@${'$'}#&%&%${'$'}%&%${'$'}&${'$'}B8&${'$'}%W8@&@${'$'}88@xfov7B
                                                             @+ !=&7f&#&%&#&#@%&#&#&#${'$'}8@#&#%#&7&#&#%#${'$'}7%#%7&7%7%:~/o7${'$'}##v#v#7%#&${'$'}&7&#&#&#&#&%${'$'}@7ofov&8${'$'}@@#x#ffo#@
                                                             %#+*${'$'}&f%&@%${'$'}%${'$'}%${'$'}${'$'}${'$'}%${'$'}%&%${'$'}&B${'$'}&%&#&%&7${'$'}#&%${'$'}#%7%#%&&#&%#v7=**&#&#&%${'$'}#%#@${'$'}&#${'$'}%&%${'$'}%${'$'}%@&8%vxff&%8&#${'$'}${'$'}#%fvvB/
                                                              &&f${'$'}f7#&&&#%#&#&#&%%#&#${'$'}7${'$'}#%#%7%7&7%7&v#v#f7v&&7v#!     :+f#v#77v%#@7%#%#%7%#&#${'$'}#87xof7##B@B@@#&#%@7
                                                               =Wv@7${'$'}&@%&#${'$'}%&&${'$'}%${'$'}#&%${'$'}&x*@#&#%7%#&7&7#v%7#v#%${'$'}v#7=      :!+%#7#%7%%87&#&#&#${'$'}%${'$'}&${'$'}&B7vx##@BB8WBW8MB8.
                                                               ${'$'}7${'$'}%%%${'$'}%&%&#%#@#&&&7%%${'$'}+|7${'$'}7&7#v7v%7%v7f7f7f%f#o#+:      ,-,|f7f%7&${'$'}#7%7%#%#%#${'$'}%&@W#&&@%B@B${'$'}8${'$'}8&@@8#
                                                              +@${'$'}8${'$'}&@${'$'}@%${'$'}%&%${'$'}${'$'}${'$'}#@&&#&%${'$'}#v%%78v#v#v%7&v7f7v#v=vvv+: :+**+*7=|+v8f7#${'$'}${'$'}&%%7&#&%${'$'}&@%@BW8W8W${'$'}8${'$'}8${'$'}8${'$'}@&@&@8B|
                                                              &#&M7${'$'}%${'$'}&&#&#&#@%%#&7#%&;~,+7###f7fvx7f7vvovx#~vo#:-/?+=${'$'}B${'$'}B8W8MB8&&#@v%7#v#7&7%&&7@8B@@&@&${'$'}&@&${'$'}&${'$'}%${'$'}&${'$'}%B@8.
                                                             *@&M8&&${'$'}&@%&%${'$'}%&&8#&&&###!:  +777%v#fvxfx777x#f~x7x~:|=${'$'}8M8W8B8MBM8MBB%%7%7%7&%&#@%&&M88${'$'}8${'$'}@&8${'$'}@&@&@&@${'$'}@${'$'}@${'$'}W8B!
                                                             @#@@B#${'$'}#@%&#&#%#&&%7&&#v&-   ,;fvfof#oxox?vf7f/:vx~:-|#&%&8&@#&${'$'}8${'$'}W${'$'}@@B@@v77#7%#%%&%&8B@@&@&@&${'$'}&@&${'$'}&@&@&@&@${'$'}@%8@8#|
                                                            ;@${'$'}${'$'}BB@${'$'}${'$'}${'$'}@%${'$'}%&%&%@&&#B7##;  ,;/?o7v*x77vofo#vvxv7o!~,;+*|B@8${'$'}B@8&@${'$'}M=7@87#7&7%#&#${'$'}#@%W8${'$'}%@@B${'$'}8${'$'}@${'$'}@&8${'$'}@${'$'}8${'$'}@${'$'}@${'$'}@&@%%@W8B${'$'}o
                                                            f@${'$'}#${'$'}B${'$'}8#${'$'}%&#&#%7%#@7%&vx7|;?&*&%&##fx:oovxvx*|!*-    ,:  &${'$'}vv${'$'}B%xx%${'$'}+oo%7f%f77%7v#%&%${'$'}B#fxvv&&&#&&@&${'$'}&@&@&@&@&@%${'$'}&${'$'}#&%${'$'}%8${'$'}8&@
                                                            ${'$'}%8|8BW${'$'}&&@%&#${'$'}%%#%${'$'}&#@=xv#788M8M8B8B&x!x=+~  -:-         ~%f?*!/=x7${'$'};!~&f%##7%77v&%W%88W77xfo+/=+?ofof?fofxvx7f7#${'$'}%&%${'$'}&${'$'}%@${'$'}B${'$'}
                                                          *#&@v-@@8&#@%&#&#&7%7#${'$'}&%=~?@WBB&W${'$'}${'$'}&o&8${'$'}|--,:             :,;vf+++?f?!|~xv7#7v#xvf%7&B8${'$'}88B%o!++/|+|/|/|?*=|+|+/=+**#@#%@&@&=
                                                !*o|  *x7v7,8@##8fM%${'$'}@@%${'$'}%%#&7%7@@@=!&MB8=W88${'$'}@@B${'$'}@@;,               :;~|!/|+|+|+|+7#&vf#f7f%%%@W@B@W88@87#${'$'}f/+|+/?/=/*@B%${'$'}&${'$'}@@%
                                                     :      o878&B@${'$'}#8&&#%7#7#v#v@&??@@8+/${'$'}B%&7@${'$'}@*#|                :-;~|!/!/|/|=vv&vf7ofx777&B${'$'}@@@@@&@&@${'$'}8${'$'}@&B#${'$'}@%7${'$'}7!
                                                             ${'$'}BBB%B8${'$'}BM&${'$'}#&#%#&7#v887@B&= ~${'$'}87v?*!=v~      -         :;~|!+|+/=*v7v7&f7fvf%##&B${'$'}B@@${'$'}W@8${'$'}8${'$'}8@8${'$'}8B&
                                                             %@W#8${'$'}B%W@8#&#%7#v#v7vBB8v&v~  /f7%?*~~:-,    ;.        :,~-!~|!/;|!|x&xvxvv%${'$'}#%B&8@87${'$'}8B${'$'}${'$'}%@&@${'$'}8&@8!
                                                            xWB${'$'}B8B8B8B8B%${'$'}#%7#7%%#7B88//~!!+!+++||;!~~,      ~  :;-;:-:;~!;!!x*ffvxvv${'$'}${'$'}8@&${'$'}W@8&@%&&W@W${'$'}@%${'$'}${'$'}B@B${'$'}8@
                                                           ${'$'}8%&8@8@8@${'$'}888@&&v7v#v##%f%#@v!;|!|!/!|!!-~::   ~/*+*=*?ov#, .::~,-~+*x?7${'$'}&&B&${'$'}&@&${'$'}#&7%7@${'$'}8@8%%#&#@${'$'}B&@&
                                                         -M8%%@${'$'}8@B8B8WBW@@@%7%v7v##&&B%&%o*/!+|/!/;;-~, ,&%7??=?=?=?f*    ,:,-:~|8&&%@8@@@&&7${'$'}%%#&${'$'}8@B@@&${'$'}%&%8@M@@${'$'};
                                                        7W%${'$'}&@&@${'$'}@${'$'}8@B${'$'}8#v#@8@7%7vxvx##${'$'}v&&|!|;|!|~;:-,: -x#??+*+*+*+x         -*8#%7%&8${'$'}@7&v&7%v%#@#B&@${'$'}@%%#&#${'$'}8&&%%v
                                                      +@W${'$'}8${'$'}8${'$'}8${'$'}8@B${'$'}%v7v@${'$'}${'$'}&BBB@&&${'$'}v#v7f&#${'$'}${'$'}fxo!|;;-~:    -!x*?=?=?=*,       ;%&@%${'$'}%${'$'}&@${'$'}8#&v&#%v##@8&88@B${'$'}@%${'$'}%&%@B;%8%&
                                                     8@${'$'}&@&@&@${'$'}${'$'}fvxvofxvofv%${'$'}8@B&${'$'}&8&${'$'}%%%&#&${'$'}${'$'};~,-,-.,       -:;:-.       -7%&7%#&#&7%${'$'}@%&v#77f7v#8B78${'$'}@&${'$'}#@#%#&#&&  #%&?
                                                  -8W%@@8${'$'}8${'$'}@7vofofofovxv%8@8@8B@&@${'$'}@${'$'}@&@${'$'}${'$'}%@${'$'}B${'$'}${'$'}~-: .      :.-,:     --fxv8%#&#&%${'$'}%&%B${'$'}${'$'}v#7#v#7#@B8&@B${'$'}8${'$'}${'$'}%${'$'}&${'$'}%${'$'}#B#  -#&7
                                                #B@@${'$'}@%&xx?o?x?xof?xx@${'$'}${'$'}${'$'}8${'$'}@${'$'}8${'$'}B8@%${'$'}%${'$'}#&%${'$'}&@%@#%%@&${'$'}#%7#7x||~-,-:-.~*fxv?o=B#&7%#${'$'}#&#&${'$'}${'$'}f7f7f7v7&8&B${'$'}8${'$'}8${'$'}@%%#${'$'}&&%&#@#   -##*
                                              ${'$'}8B${'$'}${'$'}%7oxo${'$'}8%oxo@%W${'$'}@@W@B${'$'}8@8${'$'}B8B@BB8#&f#%${'$'}#%&8@8%@%${'$'}%&#&%&%${'$'}${'$'}B8%v%%%7f?o*o?f@${'$'}&@#&&${'$'}#${'$'}@&v&v7v77%@B${'$'}88W@8@8${'$'}8%${'$'}%${'$'}${'$'}8&${'$'}%@&    |%%
                                               ${'$'}&@%%#${'$'}%${'$'}=-#${'$'}%#-   |B&@@@&@&8${'$'}8${'$'}8&@@8v7fvx7v#7&&8%${'$'}#&#%%&7%${'$'}7=#f#fxf?=*=vvo*?+f@&7&&@%#&&xffvf#${'$'}8&@${'$'}@&@&@&@${'$'}${'$'}7%#&%8&&#&${'$'}=    v#
                                                                 ~B${'$'}8BB@8${'$'}${'$'}@B${'$'}B@@88@B%${'$'}%7f#v#v#&8&@&${'$'}%8%&%B?*+?=*+of77%*?=?=*=B@@&8&88#ffv7x%@8${'$'}8@8@8&B${'$'}@@8${'$'}&#${'$'}%&&88${'$'}%${'$'}${'$'}@   !#%
                                                                +8&${'$'}#W&@${'$'}${'$'}%@${'$'}8${'$'}@@#of!=v&#%7#77xff7%@&@${'$'}B@B${'$'}@&v==+*+%f?o7=*+=+?*%@8%@@#fxx#x%@@%@&%+*+*/8${'$'}8${'$'}@#&#%#&&@${'$'}8&%#@&%f&%#   /f7&
                                                                8${'$'}${'$'} B${'$'}8${'$'}${'$'}%8@B@B${'$'}8v7?v  !x7&%&#%fvv&v8%W8W8B8W8W8W${'$'}8&@%${'$'}8B=8@WBW8WBMB%vfo#7#B8v7vf==/=~ 7B${'$'}888%${'$'}#${'$'}%&&88@${'$'}8%8;;7@%f &7|/${'$'}
                              -                               ~${'$'}#- ?@@&&#%${'$'}8&B?o=*=ox!    |?7&#%vf7vo#@B@8@B@B@B@BB${'$'}77#${'$'}${'$'}W8W@B8B@B8B%xofx7%${'$'}#7?*//     +@${'$'}&@${'$'}%&&7%7%#${'$'}${'$'}&.%${'$'} .@${'$'}&7&?!~@      /=
                             +                               !&f  !8@%${'$'}%&${'$'}B${'$'}8@?=*+*+*ox   ;~|+f&${'$'}v%7ffB8B@B8B8MBMBM&%7${'$'}%&8MBW8W8W8W88ovx%8@=fof?*/!   *?W${'$'}8${'$'}8${'$'}@&&#${'$'}%${'$'}%@${'$'}8|${'$'}-;&&${'$'}8;~-${'$'}-   v##o@
                            o                               |${'$'}~  +@&#&#&&8&@${'$'}8/=+*/=+&&       |x=${'$'}7#of7W@B${'$'}8@8${'$'}88W8MB${'$'}7BBM8B@8${'$'}8${'$'}8${'$'}B@fox788?fx;- |-   ?%%B&@&@&${'$'}%${'$'}%!?${'$'}%&%Bx;~${'$'}%${'$'}:;-@? +#7!-x%:
                           -#                              ~@v  =@&%${'$'}%&&B${'$'}8${'$'}8@${'$'}+*=foMff~/;~!?=?=xf@fvov8W8B@WBW8MBMBMo%vMWMBMBW8B@B8Bf%of7W&x??=+~    =ff&8${'$'}8${'$'}@${'$'}8&${'$'}%|!o${'$'}${'$'}@|-  ~~;-${'$'}@W&*~!?${'$'}/
                            v                              ${'$'}#: &%%#&#%&@%${'$'}${'$'}8${'$'}${'$'}fo*o7W87       =+ox7#f?#x7@B8W@8BM8B8W@*+?#W8W8WBW@B8Bov#x?f#8,*-;       fv?@@&${'$'}&${'$'}&@#${'$'}v~,f7=~~,-:-.-:+~;:vx#vxv7v@
                            &~                            ${'$'}&@%@7&%${'$'}%${'$'}${'$'}8%${'$'}${'$'}88v+?=?#MBW8-      -+:xvvv@8vv*@B${'$'}o8W8W8BB%==!=${'$'}WBW8WBB%87?=&#fovx7?=|! +    %xfW${'$'}@&8${'$'}@${'$'}8%@,-  :|/|-!~;:~:;~B@%=!;f7#
                             &|                           %@&%7&%&%@&${'$'}#&&8&8v*+*oW8B@W@ +|?voxo7f777#W7*!   @8${'$'}8${'$'}8${'$'}B|?!/|*88@B${'$'}8@8   +?#x7#xo7fvf7*  +,B88${'$'}8&@&${'$'}%${'$'}&${'$'}@  :   :|!-!;!-::--~~7f7;
                             :&7                         #8%&%${'$'}%@@8${'$'}@%&&8@8${'$'}W&??MBB@B8W@88f =*fx7f%*f&W*~;!&B88@B@8@-= |  xB8@8@B@B/~! +v?B@vxf*/-|;!${'$'}W8WB=@@${'$'}8&@&W${'$'}Bf~,-  ,/!!~!~;,~:+${'$'}8#@o
                               %##x                  =o#7%#&#&${'$'}8${'$'}@%&7%#@&@&@${'$'}*&B&8@8${'$'}8@B@B@~;|!+/*vvx#8&.-%@&@&@${'$'}@${'$'}7;o:   -7${'$'}@${'$'}@%@${'$'}Bf~  :/#8of+/-     ${'$'}B@B8B#${'$'}&${'$'}%Boo=&x~,, :,~,-.,.-:@${'$'}${'$'}#%7%7#-
                                 v&&&7%v=,     -=x%#&%${'$'}%&${'$'}@${'$'}W@8${'$'}@#&%&%8${'$'}@${'$'}@${'$'}B8BBB8W@8@B@BBo    ;oo%7*!B${'$'};~${'$'}&${'$'}${'$'}B@@&B/||= ~;  ${'$'}${'$'}@${'$'}8${'$'}@&${'$'} + ;-f8?*|;!-   =M8B8W8W${'$'}${'$'}&${'$'}8 ;x${'$'}o+=!;-~:-,:.~7B${'$'}${'$'}${'$'}@%@%&v%7/
                                   :v#7%%${'$'}#%#${'$'}%&#&#@#&%&#/*8${'$'}8&&#&#%%@&${'$'}&@${'$'}B&8@8${'$'}8${'$'}8${'$'}B${'$'}@${'$'}B8|     |!o::%@!~  |==${'$'}&8&!:|+!    =@${'$'}@&f!+  ;    87x|;    ;W@8@8${'$'}8${'$'}88${'$'}@B / v*x?*+=|!-~?#${'$'}8&${'$'}#x%f!%%@#77%=
                                         ;&%@@${'$'}%${'$'}v|     &@B&8%${'$'}%${'$'}&${'$'}&8${'$'}@&888${'$'}B@8@B@8@B8B@B8WB8 | ;;+/=~7@v   !  - ;//-:|| /7! =?~!: ;; !    &${'$'}o+|;|@${'$'}M8B@B@B@B@8BB8++  ; ~x?+*=%%f?o*W&${'$'}#o&|  -&#${'$'}#${'$'}##-
                          -!~!-       ,#7%#${'$'}7;       ?%8&${'$'}&${'$'}%${'$'}%${'$'}#&%${'$'}#&&888@B${'$'}@${'$'}8&@${'$'}8&@${'$'}8${'$'}B8B@B88  ;//~|8#   .  ;   = --~v8&Bf=! -!  ~  !   %%x|  @BB@@${'$'}8${'$'}@${'$'}8@8B8${'$'}8&*     =?*+#xo**+*&@#@!v%     ~&###&7|
                    ;x##%v77%#${'$'}%vv##%%@#%&${'$'}##v7v%&${'$'}${'$'}8${'$'}@${'$'}${'$'}%${'$'}%&7&#${'$'}&@%${'$'}${'$'}8WB8W8W@B@8${'$'}8@8${'$'}8@8@B@B8WB+!! ~! ${'$'}@   ~  -   +  /; &@&:=;  /  |  ;   f8**+|@W88${'$'}B@8@BBWBB8B@B& !  ;: o#xo*f=?=*&8%${'$'} @v      +${'$'}#&%%%:
                  f%fx?vovox|/;of&7#fvv%7#v7v#v##%7%%&#&#%7#7&%${'$'}%&v${'$'}&@&${'$'}BB@B8B@8@B@8@B@8${'$'}8${'$'}@${'$'}B@B   |=x ${'$'}${'$'}   .  ~   =  ;|,    *|~ +  ~  !   o${'$'}x/ fW@8@B@8${'$'}B@BBM${'$'}8${'$'}8&@${'$'}& +/       |o*?=W#&& %&        #%%:f&v
                %${'$'}vf&v         =@#${'$'}${'$'}@%#7&%&#&%&#&#%7%7%7%#%#${'$'}&&7%%@&@%%%@WB8W8W8W8W8WBW8W8B${'$'}B@W8Wfv; *-7W*  ~  ,-  +  |!/    ~+  =  |  !   oB*&@MBB8WBW8MBWBWWB${'$'}8${'$'}B@B@B${'$'}f~+|/++ ~-|#8@@&8f@%/        %${'$'}%  %7
               77v%=          ?8#%%&#%%${'$'}7#7#v#7#v#v#v#7%#%7#v#7%%${'$'}#%7%#&7#8B@B8W@8@B@B@B${'$'}8@B${'$'}8@B@W8${'$'}~o/!&%   ~     *: ;=~    ;=! =  ~  /  ,f${'$'}o8W@B@W8B@B@WB8@B8B&8${'$'}B@B@8${'$'}8${'$'}8?++${'$'}${'$'}B${'$'}${'$'}f%#&&@#@+         %${'$'}   #v
               %${'$'}&         x&%%f${'$'}&${'$'}%&#${'$'}%${'$'}&@%%7%7%7&#&%${'$'}%%7&#%#${'$'}%&7%#%%${'$'}#%7&@WBW8W8B8W8W8B@W8W8W8WBW@~-=;8${'$'}   ;     =  !+/!7%*~o: +  |  ;  ;f8BMBW8W8B8WBMBB@B@BBW${'$'}88W8W8W8B8B@WBWB@&#v&%${'$'}&${'$'}${'$'};         v${'$'}x  x${'$'}
              x${'$'}#     ovv#77f7x&&&#&#&#&#&#&%${'$'}%${'$'}&${'$'}%%7%7%#&#&%&7#7%7##&7#7&#&%M@8@B@B88${'$'}B8B8WBW@B8W@B88o+${'$'}B   |  :  =~ -+-${'$'}${'$'}8${'$'}!+  *  |  ~  !%@WBW8W@88WBB88${'$'}8@8${'$'}8@W8B@B@W@8%88B${'$'}B%&%${'$'}v7v&%##${'$'}          #%#  7%=
              &v${'$'} -#@%f#&v#v#vf&${'$'}%${'$'}%${'$'}%${'$'}&@${'$'}${'$'}#%7#f##%#&#%#&#%7&#%#&%&7&#%7${'$'}&&#M8B8W88@B@8@W8W8MWMBMBW8WB7!%8/  +  +  */ |+~ =/ +=~|+  = !! !/${'$'}BBM8W8MBWBB8B@B@B@8@B8W8MBW8M8W8W${'$'}%#${'$'}%${'$'}&7v7%@7v&@        /@7@ /%${'$'}
              #vf%%vx${'$'}%7v%xvxx?${'$'}#&#${'$'}#&##v#7#v#f7v${'$'}${'$'}#v#7%v#7%v#7%##v#v#v%#%7B${'$'}8${'$'}8&@${'$'}8&8@B@8@B@M#%BB8B${'$'}B&//Bv|/%#${'$'}%@&${'$'}&@${'$'}8&@&@&@@@&@%%?;+!~! 8&W8B@B8B8@@B${'$'}@${'$'}8${'$'}@${'$'}8${'$'}@${'$'}8@B@WB8${'$'}8v#7%#%%${'$'}ovf&%vxv&7-     =${'$'}v%7v#&=
              &77v%%${'$'}&7v&vvxfof${'$'}${'$'}%&-           7${'$'}#&#%#${'$'}#%7%7##%%#v%7#7%#%#%BB@B@B@B@8@B@B@W8M#%#8BWBW@W${'$'}@8WBB${'$'}8${'$'}8@B@8${'$'}8@8${'$'}8@8${'$'}B@8${'$'}8@B8W@B${'$'}8BBWMBW8BBB${'$'}B@8@B@8${'$'}B@8${'$'}B8B@BB%#&#%7%#${'$'}#@vxf#%${'$'}x7f7#%#  !%%v%&&7#-
            v@v7#${'$'}, %vx7v#oxoxo@%            ?#7%%7#7%#&f7f##vv7f#v#7#v77&8B@B@B@8${'$'}8${'$'}8${'$'}8@B@W8%7#vM@B@8${'$'}8${'$'}8${'$'}B@8=*=B@8${'$'}8${'$'}@${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}*f8@B@88MBB88@B@8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8@8${'$'}88%7%&#7%7%%&#x?77&xfofx&#v%&#7f7#7=
           &%%&#7#%&7f7vv7#ofof&=     o     #%*%&#%v##&&7v%#+7#v##&7%7#vM8B@B@B8B@B@B8B@B8W8o7@${'$'}8BB8W8B@B@B8W88f888${'$'}B@8@B@8@B@8@B@88@xv@W8B8WB%WW8B8W@8@B@B@B@8@B8B@WB${'$'}7%#@%%#%%${'$'}&vov#${'$'}vvxvf7%${'$'}#v#&v7%f
          %#v- |7vf${'$'}xox7oxf7oxox#+   7+    7#x%#fvfvv%f${'$'}xvv!fvf7f%##v%%8B8@B@B@B@B@8${'$'}8${'$'}8${'$'}88*+**o=&8B@B@B${'$'}8${'$'}B${'$'}8${'$'}B${'$'}@${'$'}8&@${'$'}8&@${'$'}8${'$'}@${'$'}8&@${'$'}8${'$'}888${'$'}8@B8&7${'$'}8B@8${'$'}@${'$'}8${'$'}@${'$'}8${'$'}@${'$'}8${'$'}8@B${'$'}##%v%f%v#7${'$'}%%?xv&ffofxvx&&%?77&x
         #&v    -#7v7*xv7?xf#v7fx7${'$'}- v#*  ;&&%%v7f%%7x${'$'}%v7o77f7v#${'$'}@&%8W88${'$'}B@8${'$'}B8B8W88@B@BBo=*+*=?@B8W8W88${'$'}B8B@B@B${'$'}B@8${'$'}B@8${'$'}B@8${'$'}B@8${'$'}B@8${'$'}8@B8W8M&x=*${'$'}M8B@B@8${'$'}B@8@B8B8W7%7&### *${'$'}7&&${'$'}ov#&oxovf7f7#@%ff&7%
         ${'$'}#       x%ff?x7v*o?vovf7x%v#7777#%7%7#v#ofx${'$'}vvv#fvx7xv7${'$'}#BB8@8@B${'$'}8${'$'}8${'$'}8@8${'$'}8@B${'$'}BB7+o?fofoW@B8B@B@8${'$'}B@Wx+/8@8${'$'}8${'$'}@${'$'}8${'$'}@${'$'}8${'$'}@${'$'}8${'$'}8%7%8@B@BB#+=/=+88B${'$'}8${'$'}8${'$'}8${'$'}8@B@#v#7%7#v7  %#%&o##%?x?ffvovf${'$'}=7o77#7#
         ${'$'}=        %7vvx##v*oox?xxffvxv#&v#%&vvofo7%@7%%${'$'}77f777f${'$'}BW@B@8@B@8${'$'}B@8${'$'}B@B8W8BWB=xof?oovB88MBW@B@B8W8&of@B@B@8@B@8@B@8@B@8@xof@WBB8WWoox?*+?8W8B@W8W@#v%7#7%#&7%  ~@&${'$'}f${'$'}%vofx7fvovf#${'$'}7f7&8#v%
         &+         #v7#ov77***o*o?xoxofoff%vvf%#&#${'$'}7%##v7ovx77B8@${'$'}8${'$'}8${'$'}8&@${'$'}8&@${'$'}8&@${'$'}8${'$'}88W+=+*===${'$'}@${'$'}=?+f&@&@@W88@B@8${'$'}8${'$'}@${'$'}8&@${'$'}8&@${'$'}8${'$'}@${'$'}8${'$'}8@8@W@B@@oo=x?=/+/x&8@7v###f7v#vf##   %%%7#ox?fxf?o?vfv&%xv7-|@ofv
         x&-      ! ~&&#%7#v#ofoxofxfofxfofofofxfxv#%v#vvv#v#8M8B@B8B@B@8${'$'}8@8${'$'}8@8${'$'}B8B8M=??xo88B@W+?==/+|~ --~;=x&&@&@${'$'}@${'$'}@${'$'}8${'$'}8${'$'}8@8&@${'$'}8%@&#/++78?=?=*+*+*=x8#v#7&##v#7#%7  f${'$'}${'$'}#x7xf?o*o*xo7f7vfo%x   &x?&
          !#7v, |vf   7&#%7%&#fvof?xox?xovoff#f7v&##7vov${'$'}B@8@B@B88${'$'}8${'$'}8${'$'}@${'$'}8${'$'}@${'$'}8${'$'}8${'$'}8${'$'}B8W*xof@W8?+==f==/+!/!!   ::-:                    -::,;/=+W&${'$'}${'$'}f==+*+v@7f7v7#%#%7%%&7#77fvfvoxof??*ox7xvf%7o     %?x7
             !vfv?     ~${'$'}#%#@%%7%77xvf7fvxvf%7&7%v%7#&8@B${'$'}${'$'}&B@8${'$'}8@B@B@8@B@8${'$'}B@B@W8BWB=?%MWM${'$'}?+=*x%@?o?+-       :;-~,                      +=%&?=ox@?o*?${'$'}#7##&v#v${'$'}%#7%7#v#v7fvxfofxff7f7x#&x        #v7f
                         %77v&   ?v#xf#%vv7${'$'}+-&vxvf@@B@B&${'$'}&8${'$'}8&${'$'}&@%@@8${'$'}8${'$'}@${'$'}8${'$'}8@B@WBo+f@B@B@    +v*| , /&xo=?=|                         !f*=f7*#7?+|8v*+@f#7${'$'}xvxvov#%7vf7xvf7fvxf?xx7v#7%v          %x&
                          &xf%v   vv o%fvx#f   %#f%BW7vx%${'$'}B@@&${'$'}${'$'}@${'$'}8@${'$'}@W@8@B8B@B8M&W${'$'}WBWBW8Mf/=?v*/ -|  :f+=!~:!!++*+=!=+=!|;!|+=oo*!o=vff !-//*vv==fM8@@7x%fvf7fvv%%%#%7%7%7#v%7#7#v7v%*          o&&
                ~f        x#?#+vfo+%o%ofx7*  7fv7#;      |!|x@${'$'}${'$'}${'$'}8${'$'}8${'$'}${'$'}&B@B@B@B88fB8W8B@B8Wv+|/xo|! :~!   =+/!/:       ,:      :;,   ;*+  |!~!+,=*v/=${'$'}W88f7xvxvf7vfx7v#v#v#7#v7xvf7f7%o          ~#%#
                f7        #ovv  fv7f#7&v+    8*?xxf?    !~  -~?@B@B@88@8M8W8M8f@M8W8WBMf+==+oxf//   =+/    |=|+!                 ,~!/   !+/ //~ =*7?*+*${'$'}W${'$'}@f7#7|${'$'}v7xvfvf%xfofo7ov%${'$'}v&%       /%#@%|
                 vvv*  !o#v7o        ofvxvf7%#??+==x.ox     :;|+&8${'$'}8${'$'}88@8BBWv8BB${'$'}B@B@B7~    /x~/-   ~+|      |!|;~           ,.~:;;     |/  !+-  |*f!/|+%8+${'$'}%o %xvxfo%v%fo?x?ff#! x%ov7#o7v7vv7%*
                  !xvv%7%fo              :  ;%o=**%&%oxo=      :*8B8B@W@M8x&M8W8B8W8W8    +/7 !~;    !+!        ~:;       ;~;-;~;      ~|  ://!   /o7=/:~BM???${'$'}${'$'}vfv##- 7fofx7#=     #v#777&7%7!
                           xx/          +7x7v${'$'}7fx/|=*?+fo     |*+v8B@B@B${'$'}78W@W@B8B@B88~   ,?/! ~:     ;/,                ;-~-~        ;;   ~;=.    +*f;|/B8W&?++f&&|  +%ff?x&;
                           #v=#v#77v%#&%?!;-~      ,~/*=#x7? ;| =?W8WBB7MWB8WBW8W8B8MB#!/+=?;~ :-      !+                            :    ;~!/      |ox=?BW8M8*+?| v&v~#&ofx${'$'}
                            ;#xo,-,~,;-~.-:-        .,/?=?vf   =+${'$'}8W@x8W@B@B@B@8${'$'}@@B@@~; ;? -           ~:       ,:~                     ~-;-|       ;x/ +${'$'}W@88o+*|  -v&#v?f7      |                                          |f
                         !%o7ovv7ox?ooo;~:~:          ;x##v* /?=%@B%BBW8B8W8B@B@888 ||-;=*x--                     ~-~,                  -~!~!;       -;x*/+= &WB8??+=+*-+v@vvffxvf7~                                         7M@W!
                         ;v#fvxvf&fvooovf#xf|=!:    .~x%+**oo*|+%;@M@B@B@B@8${'$'}8${'$'}@@8      ~* !                         - -:-              :~:--         -o=     #W8B/=+*+=/=/#v#7#+                                            W${'$'}@&M:
                               v&oo7&7?7%77o@${'$'}|,    !#${'$'}=?*o*xx?&BWW8W8W8W88@B@B8M+    |!++!~                           ,~,             :;:~:~          ,v|+!   8W@W=*=*+*+*+*+=/7vvv                                        fW@${'$'}8@W
                              %xof%%xv?    x7|: .|vvv#x7f#v##&@W@8@B@8@B${'$'}@${'$'}8&@@B8     .!+ ~                                             .,.-.          --x!~   %@8${'$'}W==+=++/+|+/+|/!;?v?v                                    8${'$'}&@&${'$'}8${'$'}
                                  -;      ${'$'}+|-+v%*         *&BM8B8W8B8B@8${'$'}8@8@WBW&?; |=+f |                                                             ;?o/~~o@W8W${'$'}M7*=?+ -+/=/+|=/+-  %7x                                88&@${'$'}@&@B
                                         vv=?%x        ~f7vWBB@B@B@B@8${'$'}@${'$'}8${'$'}@${'$'}W8B8Wo! -!?+-:                                                             - ?=|!|B@B@B&W${'$'}=+*   //+|+|+|+!!  /@+                             @B&@&${'$'}&@&8%
                                         |&7+       ?v#7x${'$'}M8W8W8W8W@B@8@B@8@WBB@W8     * ;                                                              ~;x;  /fMBW8W${'$'}M8?=/   //=/+/=/+/=o%7                             8B${'$'}8${'$'}@&8${'$'}8%W
                                                ~vvv=**${'$'}88@B@B@B@8@8&@${'$'}8&@${'$'}B@8@@&=-  !;f ;                                                               ~ x!/;/+ &B@8${'$'}88?/    !|/|+|/=7f;                             *@@&${'$'}%${'$'}&@%${'$'}&${'$'}@
                                             o#7f=?=**W88@W8B8W8B@B@8${'$'}B@8${'$'}W88Bx      ;=? ;                                                               , o!      BW@W88B#:    ||=v%7=                              -BB@&@&@&@${'$'}@&@&W~
                                          ~%7?/=+*+*&W&8@B@B@B${'$'}8@B${'$'}8${'$'}8${'$'}@${'$'}B@8${'$'}B      :!;f !                                                                ~/?+!~   8@B@B@@8${'$'}     fv-                                @M&${'$'}&@%${'$'}&@&${'$'}&@&${'$'}8=
                                        7%f+*=*+*=#BW${'$'}B8W8B8B@B8W@8@B@8@W8B@B8     ;|!+x |                    '                                           + x;     ${'$'}W8B8W8BBW  f%!                                B88${'$'}@&@${'$'}@&@${'$'}@${'$'}8${'$'}@&M/
                                      v@|++*+=+**B8${'$'}&8@B@8${'$'}@&@@B${'$'}@${'$'}8&@${'$'}8@@${'$'}8${'$'}B         * !                       '                                        ; o/!  ;|B@8@B@8@8&B7                                 %W&${'$'}%${'$'}&${'$'}%${'$'}&${'$'}%${'$'}&${'$'}%${'$'}&@8
                                       f#7o=*+?8M&B@B@W8B@8@8@B@8${'$'}8@8${'$'}B8B${'$'}8@BBv      :=x ~                 '                     WWWW                     + o|=/|!B8B@88B@W8B@W                                BW&@&@&@${'$'}@&@&@&@&@&@${'$'}W
                                          +7?&88&B@8@B@8${'$'}8${'$'}@${'$'}B${'$'}8${'$'}8${'$'}@${'$'}B@8${'$'}8${'$'}@@B8=?/.:|/!x! :                  '                 WWWWWWW                    ! *!      @8${'$'}8@B@8@B${'$'}W@                            |8@&@%${'$'}&@%${'$'}&@%${'$'}&@%${'$'}&@%${'$'}8
                                           -MB@${'$'}W8B8W88@B@8${'$'}B88${'$'}B@8${'$'}W88@B@8@W8W8W      ;v |               '     '              WWWWWWW                    + f;!,    W88${'$'}B8B8W8B${'$'}MB8,                        oW8${'$'}8${'$'}@&@${'$'}@&@${'$'}@&8${'$'}@&@${'$'}@B${'$'}
                                          7M%&${'$'}B@8@B${'$'}@${'$'}8&@${'$'}8${'$'}@${'$'}8&@${'$'}8@MCNS@${'$'}8${'$'}B@B8;     *x  -.               '  '                                          ! =-,~    @8${'$'}@&@@B${'$'}8@B&W@B${'$'}#                     :B@&${'$'}%${'$'}&${'$'}%${'$'}&${'$'}%${'$'}&${'$'}%${'$'}&${'$'}%${'$'}&W
                                         @M&${'$'}@W8B@B@8${'$'}8@8${'$'}B@8${'$'}B@8${'$'}B88${'$'}8@8${'$'}B@88W8M&   :?|=o !-                                                            : |o+     =W@B@8${'$'}B8B@W8B&MB@${'$'}W@@/                 8B&@${'$'}@&@&@&@${'$'}@&@&@&@&@&W${'$'}
                                       78B7${'$'}@B@8@8${'$'}@${'$'}8${'$'}@${'$'}8@8${'$'}8${'$'}@${'$'}B@8${'$'}8${'$'}@${'$'}8${'$'}@${'$'}B@8${'$'}${'$'}-!.    *= ;               WWWWWWWWWWW                   v              | o!=++~|&B8B${'$'}@${'$'}8${'$'}8@8${'$'}8@B&BB@&@&8${'$'}@${'$'}@${'$'}${'$'}&@${'$'}${'$'}&#~   ,W${'$'}@%${'$'}&@%${'$'}&@%${'$'}&@%${'$'}&@%${'$'}&88
                                     -WB@&@8W8B8B@8@B@8${'$'}B8B@B@8@WBB@B@8@B@8@B8M*       =*/v+ /             W  WWWWWWWWWWWWWWWWW          -f~        %   ! ;=|    !@${'$'}W88${'$'}B@8@B@8@B8B&WW8${'$'}8${'$'}8${'$'}8@8${'$'}8@8${'$'}8@W8W@fBB${'$'}@&@${'$'}@&@${'$'}@&@${'$'}@&@${'$'}@&8W
                                    fW&&%${'$'}&8@8@8&@${'$'}8&@${'$'}8${'$'}8@8${'$'}@${'$'}8@8${'$'}8${'$'}@${'$'}8${'$'}@${'$'}8&B88      .=~|;o; ! .         WW  WWWWWWWWWWWWWWWW            ;-.   v@%@&-  ! ?|-:     ?88${'$'}8&@${'$'}8${'$'}8${'$'}8${'$'}8@8%@B@&@&@&@${'$'}@&@${'$'}@&@${'$'}@&@8W&${'$'}&${'$'}%${'$'}&${'$'}%${'$'}&${'$'}%${'$'}&${'$'}%${'$'}&@8:
                                  ~8B&&&@&B8B@8@8${'$'}8@8${'$'}8@B@B88${'$'}B8B@B@8${'$'}B@8${'$'}8@8BWB&          |x| !;        WWWWWWWWWWWWWWWWWWW            8${'$'}8${'$'}${'$'}+   :${'$'}#   ! =!-~-~    &W8B@8${'$'}8@8@B@8${'$'}B8B&@W8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8&@${'$'}@&@&@&@&@&@&@&W8
                                 %B%%${'$'}${'$'}8@B@B${'$'}8${'$'}@${'$'}8${'$'}&f8${'$'}8@B${'$'}8${'$'}8@8@8${'$'}@${'$'}8${'$'}@${'$'}8${'$'}@8B@B88~       /?|*? ~|,      WWWWWWWWWWWWWWWWW             @&@&@${'$'}8%&#${'$'}${'$'}+  ; ?f+       ~W@B${'$'}@${'$'}8${'$'}@${'$'}8${'$'}@${'$'}8${'$'}8@8#&B8&@&@&@&@&@${'$'}@&@${'$'}@&@&@&${'$'}&@%${'$'}&@%${'$'}&@%${'$'}@W?
                                WB@&%@W@8@B@8&8${'$'}B@8&8@B@B8B8B8B@B@8@B@8@B@88W8B8WBW       o|+/*x- /:      WW       WWWWWW             8${'$'}  8${'$'}8${'$'}8${'$'}8@&  ! ?o/==      BBW8B@B@8@B@B@B@8${'$'}B88%8M8${'$'}8${'$'}@${'$'}8${'$'}@${'$'}8${'$'}@${'$'}8${'$'}@${'$'}8${'$'}@&8${'$'}@&@${'$'}@&@${'$'}8BB
                               W${'$'}${'$'}%@${'$'}${'$'}@8${'$'}${'$'}${'$'}${'$'}#${'$'}${'$'}@f&7&&@${'$'}8${'$'}8@@${'$'}8@8${'$'}@${'$'}B${'$'}@${'$'}8&@${'$'}B${'$'}8${'$'}8${'$'}8@Bo +~,      **? |;      W       WWWWW              ${'$'}@${'$'}@${'$'}@&${'$'}&${'$'}#+ -- =|.  !, |+7B8B88@8&@${'$'}8&@${'$'}8${'$'}@${'$'}8&8@8#${'$'}B8&@&@&${'$'}&@%${'$'}&${'$'}%${'$'}&@%${'$'}&@&${'$'}&${'$'}%${'$'}&${'$'}&B&
                              M${'$'}@&B8B@B&B${'$'}B${'$'}8&8${'$'}${'$'}&@%@${'$'}B${'$'}B88v&#@BB@W88${'$'}8@8@M8B${'$'}8@8@W8W@BB${'$'}     *=/=f* ;       WWWWWWWWW                  &&&&@&${'$'}!::- ?x -~      =B8W@B8W@8${'$'}B@8@B@8${'$'}B${'$'}&&B${'$'}8%@W8${'$'}8${'$'}@&@${'$'}@&@${'$'}@&@${'$'}@&8${'$'}8${'$'}@&@${'$'}8BB-
                        v%v#7#@@&B@8@B@8&#&${'$'}${'$'}8&@%@&@@8%88B7&${'$'}B@@@B88@B${'$'}88W8B${'$'}8${'$'}@${'$'}8@B8B@B8@ :!!/:   ~/f                                    |+~  |; ;?/= --     *oBB${'$'}@@W@8${'$'}8${'$'}@${'$'}8${'$'}8${'$'}8${'$'}@${'$'}&${'$'}8${'$'}@#&BW@B${'$'}@%${'$'}&@%${'$'}&@%${'$'}&@&${'$'}${'$'}8%${'$'}${'$'}B8o
                      @7*/=+??xBB8B8W8B@8${'$'}&&8&8@B@8@B@88W${'$'}@%@@B87&W@8${'$'}${'$'}+#${'$'}M@B@8${'$'}B@8@W8W8W@x!      :=|/                                      !-~ ?*x=?+/     x77BW88${'$'}BBB8B@8@B@B@B@8${'$'}@%8@B88#&Wv 7@@@W8B8B${'$'}8&@@@&8@B@BB
                      o${'$'}+=+=+=+f@B@B@8@8&@${'$'}@#x7${'$'}&@7@&@${'$'}B@B8W8B8M8B${'$'}8&${'$'}%${'$'}${'$'}W@8&@${'$'}&&@${'$'}@&8@B@/        !/~/                                       *+?!;   =/~   ${'$'}8B8B${'$'}@${'$'}@&@@B@@${'$'}8&@${'$'}8${'$'}@${'$'}${'$'}&@${'$'}8${'$'}@&&%${'$'}Bx       !7@@W${'$'}8&${'$'}%@${'$'}B8
                       ${'$'}*+*+*=7xv@M8B@B@8${'$'}8${'$'}8${'$'}@%%&@${'$'}8@B${'$'}BBM@@@MWW8MB8%v@M88${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}B8B88       ;                                       f?o*         |=#W8B8W@8${'$'}8@8${'$'}W8B@B${'$'}%${'$'}B@8@B${'$'}8${'$'}8${'$'}@&B88&@B#          *M88&@&8BW
                        ${'$'}*+vox/=+o?B8B${'$'}@${'$'}8&@${'$'}@&@${'$'}@%%%8${'$'}B88#@@W@W${'$'}BBBBM@W@B${'$'}${'$'}%${'$'}#&&8&@${'$'}@@B@B@Bv          /+|     =*?=+=+             |**++||/=!o          ;B8B@B${'$'}8${'$'}8${'$'}@${'$'}B8BBB${'$'}8&@&8@${'$'}&${'$'}${'$'}8%${'$'}78@B@8%${'$'}B!          B8&${'$'}&@&@B
                        #${'$'}f+=/=+o?o*#8W${'$'}8@8${'$'}8@8${'$'}B@8@B@M8@${'$'}8BM8W8@@W8WBW8B@8${'$'}8${'$'}${'$'}&8@8${'$'}B@B8W8B8W@@      :;        |*| ~+ o*?*=+x*oo+|++! /;    ++;        oBBW8B@8@B8B887v&MBB${'$'}8@8@8@@&8${'$'}&&B@B8W88B#o%#v*     !M${'$'}@&@${'$'}@&M
                       ${'$'}*=/=/+/=/=+*??*B8@${'$'}@&@${'$'}@&@${'$'}8${'$'}88W${'$'}88W${'$'}888#@@B@B${'$'}8${'$'}@${'$'}@&@${'$'}8%@${'$'}8&${'$'}${'$'}@${'$'}8${'$'}8${'$'}8@B${'$'}&  -x*8+      |  ,-   ?!   :+=*   -:-~       |~     f${'$'}W@B8W@@@B@8@@${'$'}8${'$'}${'$'}${'$'}W@8&@${'$'}@#@#%v${'$'}${'$'}8&@${'$'}8${'$'}88#=*/!,@/    W${'$'}${'$'}&${'$'}%${'$'}&${'$'}B/
                        8**+=+*+=+?xx?f**vB88${'$'}8@8${'$'}B@B@MBWBW@88W@%@WBB@B8B%B@8${'$'}8@${'$'}#${'$'}%%${'$'}B@B@8${'$'}8@W8B8W88@M8W@8            /~    ;*      :-      78x~~8@W8W8B8${'$'}?x&@@Bf&BB&%7@8B${'$'}@${'$'}B${'$'}8#@${'$'}8${'$'}8@8${'$'}B@BB7=*=*+|${'$'}    @W8&@${'$'}@&@&W=
                         @f=/=/=+ooo/=/=+==o%8&8${'$'}8${'$'}8@W${'$'}8@B${'$'}8${'$'}W${'$'}B${'$'}W8W@8${'$'}%%${'$'}%@${'$'}87${'$'}&@%${'$'}&7${'$'}8@8${'$'}@${'$'}8${'$'}8@B@B@B@B@B@B*;                ~            |@B@B@B@B8B${'$'}8@W@${'$'}78&@@MBBBM8B8W${'$'}B@@%${'$'}&8${'$'}@${'$'}8${'$'}@${'$'}8${'$'}B${'$'}x*x+=/=&v   &8@&@%${'$'}&${'$'}%${'$'}Bo
                          %${'$'}?+=xf+=/*+=/=+o=?=*+@@B8M8B${'$'}B@8@B8W@BWW8W8B@8%#&8${'$'}x%@${'$'}8&@@8@B@8${'$'}${'$'}@8${'$'}8@B8W8B8W8W8W${'$'}-        /@${'$'}&%@%+         -#88W8W8W8B8B${'$'}8${'$'}B8M${'$'}%f8BWWMB8BMBWBB@B@B${'$'}B@8${'$'}B@8${'$'}BBB*+;/+oo?%&   8B@${'$'}8${'$'}@&@${'$'}@%M+
                            v#+#f+;!    !*+=|/;//x=*??${'$'}8${'$'}@${'$'}8&@${'$'}88B@B@8${'$'}@&@&${'$'}x${'$'}&7&@${'$'}8&${'$'}&${'$'}${'$'}8@@&@${'$'}@&@${'$'}@&@&@${'$'}8@B@88W${'$'}@&&f##B@B@B@Bo|~!|7&@@W8B@B@8@B@8&@#@${'$'}@&@@W@BBW&B8W${'$'}&${'$'}W@@8B@8${'$'}8&@${'$'}8&@${'$'}8${'$'}*=?;     *8; :B@${'$'}&@&${'$'}&${'$'}%${'$'}&#8~
                              @%%%7     /+=-   ~o=?o?+/|*${'$'}B8W${'$'}8@M8W8B${'$'}8${'$'}8${'$'}8${'$'}@&%x${'$'}&8${'$'}8${'$'}8@8@B${'$'}@${'$'}8${'$'}8${'$'}8${'$'}%${'$'}8${'$'}8${'$'}W8B8W8B@W8B@W8B@W8B@W8W8W8B@W@B@B@8${'$'}B@8${'$'}8&@${'$'}@${'$'}@${'$'}B8W@M8%@M8W${'$'}&${'$'}88W8B8B${'$'}8${'$'}8@88B?//=/=       B&M${'$'}@${'$'}8${'$'}@&@&@&@vM
                                  v&,   .      ~?x=~  ,+/=!+?&%8xB8B@8&@${'$'}8&@${'$'}8&@%&v${'$'}%@&@@8${'$'}8${'$'}8&@${'$'}@&@%@&${'$'}&@&@@B@B@B@8@8@B@B@B@B@8@B@8@8&7&8${'$'}@${'$'}8${'$'}8${'$'}8%@${'$'}${'$'}&@${'$'}@${'$'}${'$'}${'$'}B@@#B888W&%${'$'}W8B@B@@${'$'}8@8${'$'}??+   !;      %B@&@&@&${'$'}&${'$'}%${'$'}&${'$'}7${'$'}@
                                    8&         +?      ||  |f=fo*=f@W@8${'$'}8@8${'$'}8@8${'$'}8${'$'}8&@78${'$'}8@8${'$'}8@8&8${'$'}8${'$'}${'$'}%@%@${'$'}@${'$'}B@B@B@B%8@B8W8W8W8B@B@8${'$'}8@8${'$'}@${'$'}@&B@B@@#%${'$'}@${'$'}8${'$'}B@${'$'}${'$'}B8B${'$'}${'$'}8W${'$'}BBWBM@BBW8W8B#=/o?o=-         #@8${'$'}@${'$'}8${'$'}@&@${'$'}@&@&vBx
                                     ;&&~     ==           x=x//  |+/@${'$'}8&@${'$'}@&@&@&@&@&@&@%${'$'}&@&@&${'$'}#${'$'}%@&${'$'}&8&@${'$'}${'$'}&@&8${'$'}@${'$'}8&@${'$'}8@8@B${'$'}8&@&@%@&@&${'$'}%#%@&@${'$'}#&%#@&${'$'}&@&${'$'}&${'$'}#@@W@B8@&888${'$'}@${'$'}W@@==*+/+/+*x;       /B&@&${'$'}&@&${'$'}&${'$'}%${'$'}%${'$'}%7xW
                                        v7&vxf&*?+?       /?o~    +/+;|=@${'$'}B${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8@8@B${'$'}@%#%@${'$'}8${'$'}@${'$'}8@8&@&${'$'}&@&8${'$'}8@8${'$'}B@8@B${'$'}${'$'}&8${'$'}@&@${'$'}@${'$'}@${'$'}8${'$'}@@8&@##%8${'$'}${'$'}&@7&%@${'$'}8${'$'}B8W@W@8${'$'}8${'$'}8${'$'}B8oxv+o/=/+/=**     ~${'$'}B&8${'$'}@&8${'$'}${'$'}&@&@&@&%fWo
                                            ,::${'$'}7*+*%f  !=o;      ~ .  !*x+&${'$'}8@@${'$'}@&@${'$'}@&@${'$'}8${'$'}8${'$'}8&${'$'}%&7${'$'}&@7&&@&@%&#@${'$'}8${'$'}@${'$'}8${'$'}${'$'}${'$'}@&${'$'}&${'$'}%@${'$'}@&@${'$'}@&@${'$'}@&@&@%7&@7%%${'$'}&@${'$'}@&@${'$'}@&888&@${'$'}8${'$'}8${'$'}${'$'}+/ ++f*+|+|=/+${'$'}*  f8@@${'$'}@&@&@%${'$'}&${'$'}%${'$'}&${'$'}%%x%@
                                                !#%${'$'}?!77%x~       -,   -x=?+*+o&B8B${'$'}8${'$'}8${'$'}B@B@B@B@8@8${'$'}@DeepSeek&MingchengNetworkStudioB@8${'$'}8${'$'}@&${'$'}%@${'$'}8@8${'$'}8@8${'$'}8@8${'$'}8@88W888B@%/+/*!   -v*=/=+8@B8B@B@8${'$'}8${'$'}@${'$'}@&@&@${'$'}@&@&&f7B
                                                       &#              ~*=| ~+|x==+%%${'$'}${'$'}8&@${'$'}8${'$'}@${'$'}8&@&@&@${'$'}@&${'$'}&${'$'}%${'$'}%${'$'}%${'$'}&@%${'$'}&${'$'}&&mcns%${'$'}#&%${'$'}%${'$'}%@%${'$'}&@&@${'$'}@&@&@&@&@&@${'$'}@&@@8%#f+==/~~|||     *7@&8${'$'}@&@${'$'}8&@&@&${'$'}%${'$'}%${'$'}&${'$'}%${'$'}%${'$'}#%xv@?
                                                       o${'$'}v             ~?   |+f=+! |+/=+%&88B@B@8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}B@8${'$'}8@8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}@${'$'}8${'$'}8${'$'}8@8${'$'}8${'$'}@${'$'}8${'$'}@${'$'}8${'$'}8@W@@f?+//oo?=/   -       v88${'$'}8@8${'$'}8${'$'}@&@&${'$'}&@&@&@&@&${'$'}%7xvB#
                                                         !#%v          ;-   !*+    |!/:;|+o=*?#${'$'}&8@8${'$'}@&@${'$'}@&@${'$'}@&@${'$'}8${'$'}@${'$'}8${'$'}@${'$'}@&@${'$'}@&@${'$'}@&@${'$'}@&@${'$'}8${'$'}@${'$'}@&@${'$'}@&8${'$'}8${'$'}@#=|/|+/|   !?=!          !&B${'$'}@&@${'$'}@&${'$'}%${'$'}%${'$'}&${'$'}%${'$'}&${'$'}%${'$'}%&7fxv@%
                                                            !#%##     ;v%~  *+     :    ;|f=?/=/+?*xf%@&8${'$'}@@B${'$'}B${'$'}8@8${'$'}8@8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8@8@B@8${'$'}8${'$'}#+?f*?*/; ~!=      */        fB88${'$'}8${'$'}@&@${'$'}@&@${'$'}@&@&@&@&${'$'}%&vvf78@
                                                                -vv7x7v* xv${'$'}+            !o=|/|+!??+//;/!/|+!+o?*f7&%&%${'$'}%&%${'$'}%&%${'$'}%${'$'}&@%&%&fv=*|+|/;!  ;=o?!!    .      -?:   ~7${'$'}${'$'}@&@&@&${'$'}&${'$'}%${'$'}%${'$'}%${'$'}%${'$'}%&%${'$'}#%vvofxv@v
                                                                           ${'$'}+            ;o/+|=/=o=;   ~+|/  /f?*/=+xo*/||=/=/++??x+=+=?o*=;  /~!     o*+;         :@88%@${'$'}B@@${'$'}8${'$'}@&@&${'$'}&@&${'$'}&@&${'$'}&@&${'$'}%${'$'}#vxvxvx88
                                                                            x${'$'}v#:        |%%/+|//?            /f|+|+??!   !+!   |f=+/+/=o+,    -       ?/|       -&B&@&${'$'}&@&@&@%${'$'}%${'$'}%&%${'$'}%&%${'$'}%&%${'$'}#&#7xfxvxfvBf
                                                                                v77x%x%&%7%*v#7v@             |==/+|x~           o=/=/=/?!             =8%|!*7##8${'$'}@&8${'$'}@&@${'$'}@&@&${'$'}&@&${'$'}&@&${'$'}%${'$'}%&#&vvf7fvx7f%@8
                                                                                       W+*=*+*+?&#            .f?*+=|            */+|++*!:            .&&vv7ofxfoff##&#&#&#&#&#&#%#&#%77xfxfofxfofxv%8x
                                                                                      ${'$'}&?=?=?=?=*=%%%7##+-   ,/@ ;f@             !B#%7%f@       ;*&%&%@fvxvfvxvxvxvfvxvfvxvfvx7fvxvfvxvfvxvfvxvfvx8@=
                                                                                      ${'$'}?=*+*=*+*+*+*=?+**f7B%7=    of#x#x&f#ffvf77f=+*+=?7xvfx8&v7ofxvxfxvofxvofxvofxvofxvofxvxfxvofxvofxvofxvf@&${'$'}-
                                                                                     &&=?=?=?=?=?=*=?*?=o*xB             Bx+?*?=o=*+?=*=?=*=?=B77fvxvfvx7fvf7fvx7fvx7fvx7fvf7fvxvfvxvfvx7f7@B@&
                                                                                     8+*+=+*+*+*+*+*+=+*+*@?             %${'$'}*+*=*+*+*+=+*+=+*+*vW&8&7xfxvofxvofxfofxfofxvxfxvxfxfofxfof#8&${'$'}%-
                                                                                    ${'$'}%?++~- :/*=*+?=*+?=*?W              -B=*=?=*+?=*+?=*+*+*=?B  *${'$'}8${'$'}@@87#fvxvf7x7fvxvfvf7fvx7#8${'$'}8${'$'}@&o
                                                                                    @=-:       |/=/=+*+=+${'$'}7               ${'$'}*=*+*+*+=+=/+!~.:~=/B/       .+f${'$'}#@##&@#&#&#${'$'}${'$'}&#${'$'}7${'$'}x|
                                                                                   |W:            ~!+||;*8                B=!/=+*+=//,         ${'$'}8
                                                                                   7${'$'}                  :+8                &=                   &${'$'}
                                                                                   %8                  :&7                8=                   %B
                                                                                   #@                  :${'$'}                 ${'$'}?                   ${'$'}7
                                                                                   =W                  !B                 8x                   @!
                                                                                    @                  v%                 &7                  -8
                                                                                    8?                 @+                 @#                  @o
                                                                                    =@                 8                  7%                 ;${'$'}
                                                                                     @;,              =${'$'}                  %%                ~${'$'}=
                                                                                     v#               v&                  7${'$'}                =${'$'}
                                                                                      @,              Bo                  &&                @
                                                                                     ?&|             :${'$'}%-                 %&               f%
                                                                                    ${'$'}fx%|            !8?8/              #7@v              !@
                                                                                     ${'$'}!v?v*        +v7x?&               &*#f              &@f
                                                                                    =7%=| !o*=xox?v?:+?7&;              ${'$'}*/v7*          !#@o@
                                                                                   &?| ++~|   ,|.~:-|*==/v#           #%+*+~ -=o?+!=oo*?*=+${'$'}-
                                                                                   #${'$'}  +!   |!  / !  ;*+=*8          ${'$'}*,  =+=;!       ;;o*v7#
                                                                                    ,77=    .         ~&ff            %; ~~  ~~ !/~!- ~/+ |=#-
                                                                                       #8*f*?| ,?| +??8=               x&:      !  :~  ~|  ,@~
                                                                                       7%=/=/=**/=/=+*&                 *8*?:  ?=       #vx#
                                                                                      |W;/! ||+/=/+!!!#&                @#+/*xo=o**=xox8~
                                                                                      @Wv         ,!: ~B               ?%! ~!+/=|=/+|=f8
                                                                                     +MBM| ,!-     :~ 7${'$'}               ${'$'}|        .--!;!@
                                                                                     B@B8/:           :8               8| :       ,~  +B
                                                                                     BB8M=            :@|             +${'$'}~~       -; -/MBv
                                                                                     W@8${'$'}@:* +%${'$'}&&&8&@&@${'$'}             &             !?BB@
                                                                                     8M8B8@#W@B@8${'$'}@88@B8W${'$'}           =8%@${'$'}8%%f      |@W8W
                                                                                     B8Wo${'$'}${'$'}&${'$'}B${'$'}8${'$'}${'$'}7${'$'}${'$'}8@B@W          xB@8@8${'$'}#&8${'$'}@& : oW@BB!
                                                                                     8WWv&${'$'}fB88+;     /+BW%         W@B8W88%B8B@W8@#W8B8M
                                                                                     *BM${'$'}&8Wv|           W#        W88%*!/|x#@@B${'$'}#&${'$'}7@@W8
                                                                                      8WWBW88            8M~      -W${'$'}         +oBvB&%@MB8
                                                                                       BBB${'$'}8@8x        ;&W@B      @B            7B8&@B8W=
                                                                                       8M8B@B@B8B@8${'$'}@@B8B8B${'$'}8   +BB8&          -B8BBWWW8
                                                                                       @BB@8${'$'}@${'$'}8${'$'}8${'$'}8${'$'}8@8${'$'}8@@@/ &@@@8${'$'}8${'$'}@7#xx#${'$'}${'$'}B@@${'$'}BBM@
                                                                                       *MBW@8@8${'$'}8${'$'}B8B@B@B@8@88%B${'$'}@B@B@B@8@B@B@B@8@B8M|
                                                                                        @W8W@8${'$'}8${'$'}8${'$'}@&8${'$'}8${'$'}8${'$'}8@WB${'$'}${'$'}8${'$'}8@8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8@8@W8
                                                                                         @W8MBB@B8B@B@B@B8B8M#M${'$'}B@B@B@@&8@8@B@B@B8MB${'$'}
                                                                                          oB@88W8B8B${'$'}8@B@B8WB|W8${'$'}8${'$'}8${'$'}8${'$'}8${'$'}8@8${'$'}8${'$'}B@W8B
                                                                                            o@WBB@B@8@B@88M8& M8MBW8B@B@B@B8B8MBW8B
                                                                                                ?@&@${'$'}@&${'$'}&v.   /BBW${'$'}B8B@B8B8B8B8W${'$'}x
                                                                                                                ~@B8WBB@WBB8B${'$'}x



    """.trimIndent()

// 鲸鱼娘彩蛋弹窗：ASCII 原图以等宽字体完整展示，按弹窗宽度自动缩放字号（约缩到 10%），
// 单行不换行、不变形；图较高时纵向滚动、极宽机型横向可滚动。下方按钮把原图导出为 txt 分享。
@Composable
private fun WhaleEggDialog(show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val art = WHALE_EGG_ASCII
    // 等宽字体中汉字/全角占 2 列、ASCII 占 1 列；同时统计行数，字号受宽高双约束
    val artStats = remember(art) {
        val maxCols = art.lineSequence().maxOf { line -> line.sumOf { if (it.code > 0x2E80) 2 else 1 } }
        val rows = art.count { it == '\n' } + 1
        maxCols to rows
    }
    val (maxCols, artRows) = artStats
    val vScroll = rememberScrollState()

    OverlayDialog(
        show = show,
        title = "咦，什么东西游过来了？？？",
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "哦原来是可爱的DS娘～～查看记得使用浅色模式哦！（宋体等宽效果更佳）",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(8.dp))
            // 图区尽量拉长：取屏幕高度的 58%，加上提示与按钮固定占位后整只鲸鱼也能完整落进屏幕；
            // 滚动修饰放在 Text 上，外层 BoxWithConstraints 才能拿到有界高度参与字号计算
            val artAreaMax = configuration.screenHeightDp.dp * 0.58f
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = artAreaMax)
            ) {
                // 等宽字符宽 ≈ 字号 * 0.6，行高 ≈ 字号 * 1.15；
                // 字号同时受宽度（最长行）与高度（总行数）约束，取较小者保证整幅图完整可见、softWrap=false 绝不换行变形
                val fontSp = minOf(
                    (maxWidth.value - 12f) / (maxCols * 0.6f),
                    (maxHeight.value - 8f) / (artRows * 1.15f)
                ).coerceAtLeast(1f)
                Text(
                    text = art,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSp.sp,
                    lineHeight = (fontSp * 1.15f).sp,
                    softWrap = false,
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .verticalScroll(vScroll)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { shareWhaleEggTxt(context, art) },
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
                    color = StateAccent,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("分享给其他人一起rua～～～", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("返回", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// 鲸鱼娘彩蛋：将 ASCII 原图写成 txt 文件，经 FileProvider 以文件流分享
private fun shareWhaleEggTxt(context: Context, art: String) {
    try {
        val dir = java.io.File(context.cacheDir, "shared_logs").apply { mkdirs() }
        val file = java.io.File(dir, "deepseek_whale.txt")
        val preface = "祝各位如鱼得水，生活顺利！——MingChengNetworkStudio敬上"
        file.writeText("$preface\n\n$art", Charsets.UTF_8)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DeepSeek 鲸鱼娘 ASCII")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享鲸鱼娘"))
    } catch (e: Throwable) {
        Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
    }
}

// 同步器使用/参考的开源项目条目
private data class LicenseEntry(
    val name: String,
    val license: String,
    val copyright: String,
    val usage: String,
    val url: String
)

// 本同步器（手机端 APK + 手表端 RPK）实际使用或协议参考的开源项目
private val openSourceLicenses = listOf(
    LicenseEntry(
        name = "Miuix",
        license = "Apache-2.0",
        copyright = "Copyright © 2024-2025 YuKongA & Miuix 贡献者",
        usage = "手机端全部界面组件库（HyperOS 风格 Compose Multiplatform 组件）",
        url = "https://github.com/miuix-kotlin-multiplatform/miuix"
    ),
    LicenseEntry(
        name = "AndroidLiquidGlass（Backdrop）",
        license = "Apache-2.0",
        copyright = "Copyright © 2025 Kyant",
        usage = "悬浮胶囊底栏的液态玻璃效果——实时背景模糊、鲜艳度、透镜折射与色散",
        url = "https://github.com/Kyant0/AndroidLiquidGlass"
    ),
    LicenseEntry(
        name = "Shapes",
        license = "Apache-2.0",
        copyright = "Copyright © 2025 Kyant",
        usage = "iOS 风格连续曲率圆角形状，胶囊底栏与玻璃卡片的轮廓及透镜折射依据",
        url = "https://github.com/Kyant0/Shapes"
    ),
    LicenseEntry(
        name = "XMS Wearable SDK CleanRoom",
        license = "MIT",
        copyright = "Copyright © OrPudding",
        usage = "小米穿戴互联 SDK 的净室实现，手机与手表间的消息通道（XMS）基础",
        url = "https://github.com/OrPudding/XMS_Wearable_SDK_CleanRoom"
    ),
    LicenseEntry(
        name = "SimpleFetch 互联协议",
        license = "MPL-2.0",
        copyright = "Copyright © B4QAQ / Eternal 项目",
        usage = "手表与手机间的 HTTP 代理桥接协议（SF_HANDSHAKE / SF_REQUEST 等），表端同步器按此协议实现",
        url = "https://github.com/B4QAQ/EternalAstroboxPlugins"
    ),
    LicenseEntry(
        name = "EternalAstroboxPlugins（互联协议参考）",
        license = "AGPL-3.0-only",
        copyright = "Copyright © B4QAQ",
        usage = "手动传输中 UPLOAD_FILE 逐文件分片推送协议的参考实现",
        url = "https://github.com/B4QAQ/EternalAstroboxPlugins"
    ),
    LicenseEntry(
        name = "Eternal / ResonaUI 手表端框架",
        license = "AGPL-3.0 / MPL-2.0",
        copyright = "Copyright © B4QAQ（useful.js / uiAdm.js / storage.js 等）",
        usage = "手表快应用端的通用工具、UI 管理与存储模块（global.uiAdm / storageManager 等）",
        url = "https://github.com/B4QAQ"
    ),
    LicenseEntry(
        name = "FFmpegKit（maintained fork）",
        license = "LGPL-3.0（audio 档，含 libmp3lame/GPL 组件按 GPL-3.0）",
        copyright = "Copyright © Arthenica 及社区维护者",
        usage = "手机端视频转码与音轨抽取（.bmvd 胶卷包生成）",
        url = "https://github.com/arthenica/ffmpeg-kit"
    ),
    LicenseEntry(
        name = "smart-exception-java",
        license = "LGPL-3.0",
        copyright = "Copyright © Arthenica",
        usage = "FFmpegKit 运行时必需的异常报告依赖",
        url = "https://github.com/arthenica/smart-exception-java"
    ),
    LicenseEntry(
        name = "AndroidX / Jetpack Compose",
        license = "Apache-2.0",
        copyright = "Copyright © Google LLC",
        usage = "手机端界面框架与核心扩展（activity-compose / lifecycle / material-icons 等）",
        url = "https://developer.android.com/jetpack"
    ),
    LicenseEntry(
        name = "Kotlin",
        license = "Apache-2.0",
        copyright = "Copyright © JetBrains s.r.o. and Kotlin Programming Language contributors",
        usage = "手机端开发语言与标准库",
        url = "https://github.com/JetBrains/kotlin"
    )
)

// 开放源代码许可：可滚动列表，逐条列出项目名、协议、版权、用途与仓库地址（地址可点击跳转）
@Composable
private fun OpenSourceLicensesDialog(show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    OverlayDialog(
        show = show,
        title = "开放源代码许可",
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "BiliMax 同步器在开发中使用或参考了以下开源项目，在此向各项目作者致谢：",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(10.dp))
            // 内部滚动：高度受限的 LazyColumn，滚动事件不穿透到下层页面
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(openSourceLicenses.size) { index ->
                    val e = openSourceLicenses[index]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(
                                e.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(6.dp))
                            // 协议角标独占一行：长协议（如 FFmpegKit 的 LGPL/GPL 组合）可自然展开
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = StateAccent.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    e.license,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = StateAccent,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                e.usage,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(e.copyright, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            // 仓库地址：点击跳转浏览器
                            Text(
                                e.url,
                                fontSize = 11.sp,
                                color = StateAccent,
                                modifier = Modifier
                                    .clickable { openUrl(context, e.url) }
                                    .padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("知道了", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}


@Composable
private fun TranscodeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val transcoder = remember { Transcoder() }

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf("") }
    var exportAudio by remember { mutableStateOf(true) }
    var quality by remember { mutableStateOf(FilmQuality.STANDARD) }
    var fps by remember { mutableStateOf(15) }
    val fpsOptions = remember { listOf(10, 15, 24) }
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    val tLogs = remember { mutableStateListOf<String>() }
    var result by remember { mutableStateOf<TranscodeResult?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<TranscodeResult?>(null) }
    var savedTitle by remember { mutableStateOf("") }
    // 当前转码来源的 BV 号（非空表示由 BV 号下载转码，决定 .bmvd 保存命名）
    var currentBvid by remember { mutableStateOf("") }
    // 根据 BV 号转码：BV 输入对话框 / 下载视频信息 / 下载阶段与进度
    var showBvDialog by remember { mutableStateOf(false) }
    var bvInfo by remember { mutableStateOf<BiliDownloader.VideoInfo?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedUri = uri
            pickedName = queryDisplayName(context, uri)
            result = null
            errorMsg = null
            tLogs.clear()
        }
    }

    val saveBvid = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val r = pendingSave
        if (uri != null && r != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    r.zipFile.inputStream().use { it.copyTo(out) }
                }
                // 持久化读权限：历史卡片的分享按钮需在下次启动后仍能打开该文件
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                if (savedTitle.isNotEmpty()) {
                    HistoryStore.attachFile(context, savedTitle, uri.toString())
                }
                Toast.makeText(context, "已保存到所选位置", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        pendingSave = null
    }

    fun requestSave(r: TranscodeResult) {
        pendingSave = r
        // 命名规则：本地视频转码 → 本地视频名.bmvd；BV 号下载转码 → BV 号.bmvd
        val suggestName = if (currentBvid.isNotEmpty()) {
            "$currentBvid.bmvd"
        } else {
            bvidNameFor(pickedName)
        }
        saveBvid.launch(suggestName)
    }

    // 启动转码：local 手动选文件与 BV 下载完成后复用同一套参数与回调
    // bvidForName 非空表示视频来自 BV 号下载，.bmvd 产物按 BV 号命名
    fun startTranscode(uri: Uri, name: String, bvidForName: String = "") {
        running = true
        result = null
        errorMsg = null
        tLogs.clear()
        progress = 0f
        phase = "准备中"
        savedTitle = name
        currentBvid = bvidForName
        // 日志页要能区分「主动转码」与「手表请求转码」，并看到参数与每一步
        SyncLog.i(
            "BiliMax-Transcode",
            "【主动转码】$name · 画质=${quality.label} · ${fps}fps · 音频=${if (exportAudio) "导出" else "不导出"}"
        )
        // 只在阶段变化时写日志：进度百分比回调很密集，全记会刷爆 500 条缓冲
        var lastLoggedPhase = ""
        transcoder.start(
            context,
            uri,
            TranscodeOptions(
                name = name,
                exportAudio = exportAudio,
                quality = quality,
                fps = fps
            ),
            @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
            object : Transcoder.Callback {
                override fun onPhase(phaseText: String, progressValue: Float) {
                    phase = phaseText
                    progress = progressValue
                    if (phaseText != lastLoggedPhase) {
                        lastLoggedPhase = phaseText
                        SyncLog.i(
                            "BiliMax-Transcode",
                            "主动转码 · $phaseText · ${(progressValue * 100).toInt()}%"
                        )
                    }
                }

                override fun onLog(line: String) {
                    tLogs.add(0, line)
                    SyncLog.i("BiliMax-Transcode", "主动转码 · $line")
                }

                override fun onDone(transcodeResult: TranscodeResult) {
                    running = false
                    result = transcodeResult
                    phase = "完成"
                    progress = 1f
                    SyncLog.i(
                        "BiliMax-Transcode",
                        "主动转码完成 · ${transcodeResult.stripCount} 张胶卷 / ${transcodeResult.totalFrames} 帧 · " +
                            "音频=${if (transcodeResult.withAudio) "有" else "无"} · " +
                            "${transcodeResult.zipFile.length() / 1024} KB"
                    )
                    HistoryStore.add(
                        context,
                        "manual",
                        name,
                        "${transcodeResult.stripCount} 张胶卷 · 共 ${transcodeResult.totalFrames} 帧" +
                            (if (transcodeResult.withAudio) " · 含音轨" else ""),
                        true
                    )
                    requestSave(transcodeResult)
                }

                override fun onError(message: String) {
                    running = false
                    errorMsg = message
                    phase = "失败"
                    SyncLog.w("BiliMax-Transcode", "主动转码失败 · $message（停在「$phase」）")
                    HistoryStore.add(
                        context,
                        "manual",
                        name,
                        message,
                        false
                    )
                }
            }
        )
    }

    // 根据 BV 号：解析输入 → 下载 360P MP4 → 复用本地转码流水线
    fun startBvTranscode(raw: String) {
        val bvid = BiliDownloader.extractBvid(raw)
        if (bvid == null) {
            Toast.makeText(context, "无法识别 BV 号", Toast.LENGTH_SHORT).show()
            return
        }
        showBvDialog = false
        running = true
        result = null
        errorMsg = null
        tLogs.clear()
        progress = 0f
        phase = "获取视频信息"
        bvInfo = null
        BiliDownloader.download(context, bvid, object : BiliDownloader.Callback {
            override fun onInfo(info: BiliDownloader.VideoInfo) {
                bvInfo = info
                tLogs.add(0, "《${info.title}》UP：${info.owner} · 时长 ${info.durationSec}s")
            }

            override fun onProgress(stage: String, p: Float) {
                phase = stage
                progress = p
            }

            override fun onDone(file: java.io.File, info: BiliDownloader.VideoInfo) {
                val name = info.title.ifEmpty { info.bvid }
                pickedName = "$name.mp4"
                tLogs.add(0, "视频下载完成：${file.length() / 1024} KB，开始转码")
                // BV 号转码：.bmvd 产物按 BV 号命名（startTranscode 内部不再清空）
                currentBvid = info.bvid
                startTranscode(Uri.fromFile(file), name, bvidForName = info.bvid)
            }

            override fun onError(message: String) {
                running = false
                errorMsg = message
                phase = "失败"
                HistoryStore.add(context, "manual", bvid, "BV 下载失败：$message", false)
            }
        })
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // 透出自定义背景
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = "手动转码",
                color = Color.Transparent,
                navigationIcon = {
                    top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
                item {
                    SubPageCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        colors = CardColors(
                            color = StateAccent.copy(alpha = 0.10f),
                            contentColor = MiuixTheme.colorScheme.onSurface
                        ),
                        pressFeedbackType = PressFeedbackType.Tilt,
                        showIndication = true,
                        onClick = { if (!running) showBvDialog = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayCircle,
                                contentDescription = null,
                                tint = StateAccent,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("根据 BV 号转码", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "输入 BV 号或视频链接，自动下载并转码",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                item {
                    SubPageCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                        Column(Modifier.padding(16.dp)) {
                            Text("视频文件", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (pickedName.isNotEmpty()) pickedName else "未选择文件",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { pickFile.launch("video/mp4") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !running
                            ) {
                                Text(if (pickedUri == null) "选择手机本地 MP4" else "重新选择")
                            }
                        }
                    }
                }

                item {
                    SubPageCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                        Column(Modifier.padding(16.dp)) {
                            Text("压缩倍率", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$fps 帧/秒，每 15 帧拼成一张横向胶卷，画面最长边 500px",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilmQuality.entries.forEach { q ->
                                    TextButton(
                                        text = q.label,
                                        onClick = { quality = q },
                                        enabled = !running,
                                        modifier = Modifier.weight(1f),
                                        colors = if (q == quality) {
                                            top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                                                textColor = StateAccent
                                            )
                                        } else {
                                            top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors()
                                        }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("帧率", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "帧率越高画面越流畅，体积也越大",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                fpsOptions.forEach { f ->
                                    TextButton(
                                        text = "$f",
                                        onClick = { fps = f },
                                        enabled = !running,
                                        modifier = Modifier.weight(1f),
                                        colors = if (f == fps) {
                                            top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                                                textColor = StateAccent
                                            )
                                        } else {
                                            top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors()
                                        }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("导出音频 audio.mp3", fontSize = 14.sp)
                                    Text(
                                        "关闭后 info.json 不含音频配置",
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                                Switch(
                                    checked = exportAudio,
                                    onCheckedChange = { if (!running) exportAudio = it },
                                    enabled = !running
                                )
                            }
                        }
                    }
                }

                item {
                    Button(
                        onClick = {
                            val uri = pickedUri
                            if (uri == null) {
                                Toast.makeText(context, "请先选择 MP4 文件", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            startTranscode(uri, baseName(pickedName.ifEmpty { "video" }))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running && pickedUri != null
                    ) {
                        Text(if (running) "转码中…" else "开始转码")
                    }
                }

                if (running || phase.isNotEmpty()) {
                    item {
                        SubPageCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                            Column(Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(phase, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${(progress * 100).toInt()}%",
                                        fontSize = 14.sp,
                                        color = StateAccent
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = if (running || progress >= 1f) progress else null,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                result?.let { r ->
                    item {
                        SubPageCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 18.dp,
                            accent = StateSuccess,
                            colors = CardColors(
                                color = StateSuccess.copy(alpha = 0.12f),
                                contentColor = MiuixTheme.colorScheme.onSurface
                            )
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("转码完成", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = StateSuccess)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "时长 %.1f 秒 · 胶卷 ${r.stripCount} 张 · 共 ${r.totalFrames} 帧 · 音频${if (r.withAudio) "已导出" else "未导出"}".format(r.durationSec),
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Spacer(Modifier.height(12.dp))
                                TextButton(
                                    text = "重新保存 .bmvd 到手机",
                                    onClick = { requestSave(r) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                errorMsg?.let { msg ->
                    item {
                        SubPageCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 18.dp,
                            accent = StateDanger,
                            colors = CardColors(
                                color = StateDanger.copy(alpha = 0.12f),
                                contentColor = MiuixTheme.colorScheme.onSurface
                            )
                        ) {
                            Text(
                                msg,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                if (tLogs.isNotEmpty()) {
                    item {
                        Text(
                            "转码日志",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                        )
                    }
                    item {
                        SubPageCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                tLogs.forEach { line ->
                                    Text(
                                        line,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                        modifier = Modifier.padding(vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showBvDialog) {
        BvInputDialog(
            onDismiss = { showBvDialog = false },
            onConfirm = { raw -> startBvTranscode(raw) }
        )
    }
}

// 根据 BV 号转码输入框：支持直接粘贴 BV 号或 bilibili.com 视频链接
@Composable
private fun BvInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
            colors = CardColors(
                color = MiuixTheme.colorScheme.surface,
                contentColor = MiuixTheme.colorScheme.onSurface
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("根据 BV 号转码", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "粘贴 BV 号或视频链接，将自动下载 360P MP4 并按当前设置转码",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(14.dp))
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "BV1xxxx / bilibili.com 视频链接",
                    useLabelAsPlaceholder = true,
                    singleLine = true
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "下载并转码",
                        onClick = { onConfirm(input) },
                        modifier = Modifier.weight(1f),
                        enabled = input.isNotBlank()
                    )
                }
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    var name = ""
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx) ?: ""
            }
        }
    } catch (_: Exception) {
    }
    if (name.isEmpty()) name = uri.lastPathSegment ?: "video.mp4"
    return name
}

private fun baseName(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot > 0) fileName.substring(0, dot) else fileName
}

private fun bvidNameFor(fileName: String): String {
    return baseName(fileName.ifEmpty { "video" }) + ".bmvd"
}

// 系统分享崩溃日志：报告可能很大（数百 KB），直接塞 Intent EXTRA_TEXT 会超出
// Binder 事务限制导致分享静默失败，因此写入缓存文件后用 FileProvider 以文件流分享。
private fun shareCrashLog(context: Context, report: String) {
    try {
        val dir = java.io.File(context.cacheDir, "shared_logs").apply { mkdirs() }
        val file = java.io.File(dir, "bilimax_crash.txt")
        file.writeText(report, Charsets.UTF_8)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "BiliMax 崩溃日志")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享日志"))
    } catch (e: Throwable) {
        android.widget.Toast.makeText(context, "分享失败：${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

// 系统分享 .bmvd 胶卷包：URI 为用户保存时选定的文档位置（SAF），
// 文件若已被删除/移动，打开流会失败，给出明确提示
private fun shareBvidFile(context: Context, uriStr: String) {
    try {
        val uri = Uri.parse(uriStr)
        var displayName: String? = null
        val stream = context.contentResolver.openInputStream(uri)
        if (stream == null) {
            Toast.makeText(context, "错误：无法找到文件，疑似删除或移动", Toast.LENGTH_LONG).show()
            return
        }
        stream.use {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) displayName = cursor.getString(idx)
                    }
                }
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!displayName.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享 .bmvd 胶卷包"))
    } catch (e: Throwable) {
        Toast.makeText(context, "错误：无法找到文件，疑似删除或移动", Toast.LENGTH_LONG).show()
    }
}

// 右栏功能入口按钮：浅色卡片 + 左侧彩色图标 + 标题，填满分配到的等高空间
@Composable
private fun ActionEntryButton(
    icon: ImageVector,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    glass: Boolean = false,
    hasBackground: Boolean = false,
    backdrop: Backdrop,
    onClick: () -> Unit
) {
    if (glass && GlassTuning.scopeCard) {
        GlassSurface(
            accent = accent,
            onClick = onClick,
            backdrop = backdrop,
            modifier = modifier
        ) {
            ActionEntryContent(icon = icon, label = label, accent = accent)
        }
        return
    }
    Card(
        modifier = modifier,
        cornerRadius = 18.dp,
        colors = CardColors(
            // 自定义背景时用高不透明度底色，避免文字压在图片上看不清
            color = if (hasBackground) {
                lerp(MiuixTheme.colorScheme.surface, accent, 0.10f).copy(alpha = 0.75f)
            } else {
                accent.copy(alpha = 0.10f)
            },
            contentColor = MiuixTheme.colorScheme.onSurface
        ),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = true,
        onClick = onClick
    ) {
        ActionEntryContent(icon = icon, label = label, accent = accent)
    }
}

@Composable
private fun ActionEntryContent(
    icon: ImageVector,
    label: String,
    accent: Color
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface
        )
    }
}

// 底部整行横条按钮：玻璃开启时用 GlassSurface（极淡 accent 染色，图标/文字用 accent，
// 白字在淡玻璃上会不可读）；关闭时回退 miuix 实心 Button（白字）。
@Composable
private fun GlassBarButton(
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    glass: Boolean,
    backdrop: Backdrop
) {
    if (glass && GlassTuning.scopeCard) {
        GlassSurface(
            accent = accent,
            onClick = onClick,
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(16.dp),
            pressedScale = 1.04f
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, modifier = Modifier.size(18.dp), tint = accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent
                )
            }
        }
        return
    }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
            color = accent,
            contentColor = Color.White
        )
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

/**
 * 目标设备大正方形卡片（左栏下半）：
 * 与转码服务开关同样的正方形尺寸；右上角保留蓝牙标志（与开关卡的视觉语言一致），
 * 状态颜色随连接状态变化；点击卡片可手动重新扫描连接。
 */
@Composable
private fun TargetDeviceSquareCard(
    connected: Boolean,
    connecting: Boolean,
    deviceName: String?,
    channelLabel: String,
    onClick: () -> Unit,
    glass: Boolean = false,
    hasBackground: Boolean = false,
    backdrop: Backdrop
) {
    val accent = when {
        connected -> StateSuccess
        connecting -> StateAccent
        else -> StateWarning
    }
    val statusText = when {
        connected -> "已连接"
        connecting -> "连接中"
        else -> "未连接"
    }
    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            // 蓝牙标志：右下大号背景图标
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.BluetoothSearching,
                contentDescription = null,
                tint = accent.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 18.dp, y = 20.dp)
                    .size(96.dp)
            )
            Column(Modifier.padding(16.dp)) {
                Text(
                    "目标设备",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    statusText,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "SDK 服务 · $channelLabel",
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                deviceName?.takeIf { it.isNotEmpty() }?.let { name ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = accent
                    )
                }
            }
        }
    }
    if (glass && GlassTuning.scopeCard) {
        GlassSurface(
            accent = accent,
            onClick = onClick,
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            content()
        }
        return
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        cornerRadius = 18.dp,
        colors = CardColors(
            // 自定义背景时用高不透明度底色，避免文字压在图片上看不清
            color = if (hasBackground) {
                lerp(MiuixTheme.colorScheme.surface, accent, 0.12f).copy(alpha = 0.75f)
            } else {
                accent.copy(alpha = 0.14f)
            },
            contentColor = MiuixTheme.colorScheme.onSurface
        ),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = true,
        onClick = onClick
    ) {
        content()
    }
}

@Composable
private fun ServiceCard(
    title: String,
    statusText: String,
    detail: String,
    accent: Color,
    backgroundIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glass: Boolean = false,
    hasBackground: Boolean = false,
    backdrop: Backdrop
) {
    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            // 大号背景图标（参考 IndexScreen：偏移至右下角、低透明度）
            Icon(
                imageVector = backgroundIcon,
                contentDescription = null,
                tint = accent.copy(alpha = 0.72f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 22.dp, y = 26.dp)
                    .size(120.dp)
            )
            Column(Modifier.padding(16.dp)) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    statusText,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Spacer(Modifier.weight(1f))
                Text(
                    detail,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
    if (glass && GlassTuning.scopeCard) {
        GlassSurface(
            accent = accent,
            onClick = onClick,
            backdrop = backdrop,
            modifier = modifier
        ) {
            content()
        }
        return
    }
    Card(
        modifier = modifier,
        cornerRadius = 18.dp,
        colors = CardColors(
            // 自定义背景时用高不透明度底色，避免文字压在图片上看不清
            color = if (hasBackground) {
                lerp(MiuixTheme.colorScheme.surface, accent, 0.12f).copy(alpha = 0.75f)
            } else {
                accent.copy(alpha = 0.14f)
            },
            contentColor = MiuixTheme.colorScheme.onSurface
        ),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = true,
        onClick = onClick
    ) {
        content()
    }
}

// ============================== 手动传输 / 历史 / 日志 子页面 ==============================

@Composable
private fun ManualTransferScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // 手表连接状态：与首页卡片同一数据源，推送前必须 CONNECTED
    var wearState by remember { mutableStateOf(WearState.DISCONNECTED) }
    var wearDeviceName by remember { mutableStateOf<String?>(null) }
    val wearTransport = remember { WearTransport.get(context) }
    DisposableEffect(Unit) {
        val listener = WearTransport.OnStateChanged { state, deviceName, _ ->
            wearState = state
            wearDeviceName = deviceName
        }
        wearTransport.addOnStateChanged(listener)
        onDispose { wearTransport.removeOnStateChanged(listener) }
    }

    var running by remember { mutableStateOf(false) }
    var phaseText by remember { mutableStateOf("") }
    var progressValue by remember { mutableStateOf(0f) }
    var doneMsg by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val pushLogs = remember { mutableStateListOf<String>() }

    // 手动传输：SAF 选择一个 .bmvd 胶卷包 → 拷入缓存 → FilmPush.push 解压散文件直推。
    // 不下载/转码、不推视频信息与封面；胶卷到达后由手表侧记录为「本地视频」，在设备互联页底部直接本地播放。
    fun startBmvdTransfer(uri: Uri) {
        if (wearState != WearState.CONNECTED) {
            Toast.makeText(context, "手表未连接，请先点击连接卡片建立连接", Toast.LENGTH_LONG).show()
            return
        }
        if (FilmPush.isBusy()) {
            Toast.makeText(context, "已有传输任务进行中，请等待完成", Toast.LENGTH_LONG).show()
            return
        }
        val displayName = queryDisplayName(context, uri)
        if (!displayName.lowercase().endsWith(".bmvd")) {
            Toast.makeText(context, "请选择 .bmvd 胶卷包文件", Toast.LENGTH_LONG).show()
            return
        }
        val filmName = baseName(displayName)
        running = true
        doneMsg = null
        errorMsg = null
        pushLogs.clear()
        phaseText = "读取胶卷包"
        progressValue = 0f
        SyncLog.i("BiliMax-Push", "开始胶卷包直传：$displayName")
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        // content:// 流需先拷到缓存目录成为 File，才能交给 FilmPush 解压；IO 在后台线程执行
        Thread {
            val pkgFile = try {
                File(context.cacheDir, "push_pick/${System.currentTimeMillis()}.bmvd").also { f ->
                    f.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri).use { input ->
                        if (input == null) throw IllegalStateException("无法读取所选文件")
                        f.outputStream().use { out -> input.copyTo(out) }
                    }
                }
            } catch (e: Exception) {
                main.post {
                    running = false
                    phaseText = ""
                    errorMsg = "读取胶卷包失败：${e.message}"
                }
                SyncLog.e("BiliMax-Push", "拷贝胶卷包失败：${e.message}")
                return@Thread
            }
            main.post {
                pushLogs.add("胶卷包读取完成：${pkgFile.length() / 1024} KB，开始传输")
                SyncLog.i("BiliMax-Push", "胶卷包已拷入缓存（${pkgFile.length() / 1024} KB），开始推送（$filmName）")
                FilmPush.push(context, pkgFile, filmName, object : FilmPush.Callback {
                    override fun onPhase(phase: String, progress: Float, doneFile: Int, totalFile: Int) {
                        phaseText = if (totalFile > 0) "$phase（$doneFile/$totalFile）" else phase
                        progressValue = progress
                    }

                    override fun onLog(line: String) {
                        pushLogs.add(line)
                        if (pushLogs.size > 200) pushLogs.removeAt(0)
                    }

                    override fun onDone(viaFetch: Boolean) {
                        running = false
                        phaseText = ""
                        progressValue = 1f
                        pkgFile.delete()
                        val channel = if (viaFetch) "局域网直连下载" else "消息通道分片传输"
                        doneMsg = "胶卷包已传输，通道：$channel。可在手表「设备互联」页底部「本地视频」中直接播放。"
                        SyncLog.i("BiliMax-Push", "推送完成，通道：$channel")
                        HistoryStore.add(context, "push", filmName, "胶卷包直传成功 · $channel", true)
                    }

                    override fun onError(message: String) {
                        running = false
                        phaseText = ""
                        pkgFile.delete()
                        errorMsg = message
                        SyncLog.e("BiliMax-Push", "推送失败：$message")
                        HistoryStore.add(context, "push", filmName, "胶卷包直传失败：$message", false)
                    }
                })
            }
        }.start()
    }

    val pickBmvd = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) startBmvdTransfer(uri)
    }

    SubPageScaffold(title = "手动传输", onBack = onBack, transparent = true) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SubPageCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    accent = when (wearState) {
                        WearState.CONNECTED -> StateSuccess
                        WearState.CONNECTING -> StateWarning
                        else -> StateDanger
                    },
                    colors = CardColors(
                        color = when (wearState) {
                            WearState.CONNECTED -> StateSuccess.copy(alpha = 0.12f)
                            WearState.CONNECTING -> StateWarning.copy(alpha = 0.12f)
                            else -> StateDanger.copy(alpha = 0.12f)
                        },
                        contentColor = MiuixTheme.colorScheme.onSurface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Watch,
                            contentDescription = null,
                            tint = when (wearState) {
                                WearState.CONNECTED -> StateSuccess
                                WearState.CONNECTING -> StateWarning
                                else -> StateDanger
                            },
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (wearState) {
                                    WearState.CONNECTED -> "手表已连接${wearDeviceName?.takeIf { it.isNotEmpty() }?.let { "：$it" } ?: ""}"
                                    WearState.CONNECTING -> "正在连接手表…"
                                    else -> "手表未连接"
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                when (wearState) {
                                    WearState.CONNECTED -> "可以推送胶卷包"
                                    WearState.CONNECTING -> "握手进行中，请稍候"
                                    else -> "请先在首页点击设备卡片完成连接"
                                },
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                        if (wearState != WearState.CONNECTED) {
                            TextButton(
                                text = if (wearState == WearState.CONNECTING) "连接中" else "重新连接",
                                onClick = { wearTransport.ensureConnection() },
                                enabled = wearState != WearState.CONNECTING
                            )
                        }
                    }
                }
            }

            item {
                SubPageCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    colors = CardColors(
                        color = StateAccent.copy(alpha = 0.10f),
                        contentColor = MiuixTheme.colorScheme.onSurface
                    ),
                    pressFeedbackType = PressFeedbackType.Tilt,
                    showIndication = true,
                    onClick = { if (!running && !FilmPush.isBusy()) pickBmvd.launch("*/*") }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = StateAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("选择胶卷包传输", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "选择手机上的 .bmvd 胶卷包，直接推送到手表作为本地视频",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (running || phaseText.isNotEmpty()) {
                item {
                    SubPageCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(phaseText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${(progressValue * 100).toInt()}%",
                                    fontSize = 14.sp,
                                    color = StateAccent
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = if (running || progressValue >= 1f) progressValue else null,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            doneMsg?.let { msg ->
                item {
                    SubPageCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        accent = StateSuccess,
                        colors = CardColors(
                            color = StateSuccess.copy(alpha = 0.12f),
                            contentColor = MiuixTheme.colorScheme.onSurface
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("推送完成", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = StateSuccess)
                            Spacer(Modifier.height(6.dp))
                            Text(msg, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }

            errorMsg?.let { msg ->
                item {
                    SubPageCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        accent = StateDanger,
                        colors = CardColors(
                            color = StateDanger.copy(alpha = 0.12f),
                            contentColor = MiuixTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(
                            "推送失败：$msg",
                            fontSize = 12.sp,
                            color = StateDanger,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            if (pushLogs.isNotEmpty()) {
                item {
                    SubPageCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                        Column(Modifier.padding(16.dp)) {
                            Text("传输日志", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            pushLogs.takeLast(8).forEach { line ->
                                Text(
                                    line,
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "说明：选择一个由手动转码导出的 .bmvd 胶卷包（含 info.json、帧图与音频），" +
                        "手机仅负责把胶卷包解压并传输到手表，不再发送视频信息、封面或评论内容。" +
                        "传输完成后，在手表「设备互联」页最下方的「本地视频」中即可看到并直接本地播放。" +
                        "胶卷优先走手机局域网 HTTP 直连（需开启转码服务、手机与手表同一网络），" +
                        "不可用时自动回退到消息通道分片传输，较慢但无需联网。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
            }
        }
    }
}

@Composable
private fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val records = remember { mutableStateListOf<HistoryStore.Record>().apply { addAll(HistoryStore.all(context)) } }
    SubPageScaffold(title = "转码历史", onBack = onBack, transparent = true) {
        if (records.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(it),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(80.dp))
                Text("暂无历史记录", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(it),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(records.size) { idx ->
                    val r = records[idx]
                    SubPageCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val kindColor = when (r.kind) {
                                    "manual" -> StateAccent
                                    "server" -> StateSuccess
                                    "push" -> Color(0xFF8B5CF6)
                                    else -> StateAccent
                                }
                                Text(
                                    r.kindLabel(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = kindColor,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(kindColor.copy(alpha = 0.12f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(r.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                Text(if (r.ok) "成功" else "失败", fontSize = 12.sp, color = if (r.ok) StateSuccess else StateDanger)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(r.timeText(), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            if (r.detail.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(r.detail, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            if (!r.fileUri.isNullOrEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    top.yukonga.miuix.kmp.basic.IconButton(
                                        onClick = { shareBvidFile(context, r.fileUri) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Share,
                                            contentDescription = "分享 .bmvd",
                                            modifier = Modifier.size(18.dp),
                                            tint = StateAccent
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        text = "清空全部转码历史",
                        onClick = {
                            HistoryStore.clear(context)
                            records.clear()
                            Toast.makeText(context, "已清空转码历史", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val logs = remember { mutableStateListOf<SyncLog.Entry>() }
    LaunchedEffect(Unit) {
        logs.clear()
        logs.addAll(SyncLog.snapshot())
    }
    val listener = remember {
        object : SyncLog.Listener {
            override fun onLogEntry(entry: SyncLog.Entry) { logs.add(entry) }
        }
    }
    DisposableEffect(Unit) {
        SyncLog.addListener(listener)
        onDispose { SyncLog.removeListener(listener) }
    }
    val exportLog = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    out.write(SyncLog.asText().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "日志已导出", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    // 标题下方「当前进程」卡片：连接态变化即时重绘，其余字段（转码进度/取用活动）每秒刷新一次
    var wearState by remember { mutableStateOf(WearState.DISCONNECTED) }
    var wearDeviceName by remember { mutableStateOf<String?>(null) }
    val wearTransport = remember { WearTransport.get(context) }
    DisposableEffect(Unit) {
        val l = WearTransport.OnStateChanged { state, deviceName, _ ->
            wearState = state
            wearDeviceName = deviceName
        }
        wearTransport.addOnStateChanged(l)
        onDispose { wearTransport.removeOnStateChanged(l) }
    }
    var statusTick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            statusTick = System.currentTimeMillis()
        }
    }
    val status = remember(wearState, wearDeviceName, statusTick) {
        SyncStatus.snapshot(wearState, wearDeviceName)
    }
    SubPageScaffold(title = "查看日志", onBack = onBack, transparent = true) {
        Column(modifier = Modifier.fillMaxSize().padding(it)) {
            val toneColor = when (status.tone) {
                SyncStatus.Tone.SUCCESS -> StateSuccess
                SyncStatus.Tone.WARNING -> StateWarning
                SyncStatus.Tone.DANGER -> StateDanger
                SyncStatus.Tone.ACCENT -> StateAccent
            }
            SubPageCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                cornerRadius = 18.dp,
                accent = toneColor,
                colors = CardColors(
                    color = toneColor.copy(alpha = 0.12f),
                    contentColor = MiuixTheme.colorScheme.onSurface
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(toneColor, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(status.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        status.detail,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    status.progress?.let { p ->
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = p, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(onClick = { exportLog.launch("bilimax_synclog.txt") }, modifier = Modifier.weight(1f)) { Text("导出日志") }
                Button(onClick = {
                    SyncLog.clear(); logs.clear()
                    Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f)) { Text("清空") }
            }
            val listState = rememberLazyListState()
            LaunchedEffect(logs.size) {
                if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs.size) { idx ->
                    Text(logs[idx].formatted(), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

/** 子页面通用 Scaffold：miuix 顶栏随滚动折叠，内容区自动避开顶栏 */
@Composable
internal fun SubPageScaffold(
    title: String,
    onBack: (() -> Unit)?,
    transparent: Boolean = false,
    content: @Composable (PaddingValues) -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val containerColor = if (transparent) Color.Transparent else MiuixTheme.colorScheme.surface
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = title,
                color = containerColor,
                navigationIcon = {
                    if (onBack != null) {
                        top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        containerColor = containerColor
    ) { padding ->
        content(padding)
    }
}

// ============================== 液态玻璃实验室 ==============================
//
// 玻璃的模糊 / 透镜折射 / 色散 / 高光由 Apache-2.0 许可的 Kyant0 backdrop 与
// shapes 库提供，本页仅调用其公开 API；极光背景、参数面板与交互均为本项目
// 的独立实现，未包含任何 GPL 项目的源码。

// 极光背景：深色渐变 + 缓慢漂移的彩色光斑 + 斜纹与网格。
// 高频纹理同时充当折射探针——lens 效果需要细节才能被肉眼看见。
@Composable
private fun AuroraBackdrop(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "auroraPhase"
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "auroraDrift"
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF0B1020), Color(0xFF131A33), Color(0xFF0A0F1E))
            )
        )
        fun blob(cx: Float, cy: Float, radius: Float, color: Color, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                radius = radius,
                center = Offset(cx, cy)
            )
        }
        blob(w * (0.22f + 0.12f * drift), h * (0.20f + 0.05f * phase), w * 0.62f, Color(0xFF3B82F6), 0.50f)
        blob(w * (0.80f - 0.10f * phase), h * (0.34f + 0.08f * drift), w * 0.56f, Color(0xFF8B5CF6), 0.46f)
        blob(w * (0.52f + 0.10f * phase), h * (0.72f - 0.06f * drift), w * 0.50f, Color(0xFF22D3EE), 0.34f)
        // 斜向细纹：玻璃在其上拖动时能明显看到条带被弯折
        for (i in 0 until 26) {
            val x = w * i / 25f
            drawLine(
                color = Color.White.copy(alpha = 0.055f),
                start = Offset(x, -h * 0.10f),
                end = Offset(x - h * 0.60f, h * 1.10f),
                strokeWidth = 1.6f
            )
        }
        // 彩虹色带：色散效果的参照物
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(
                    Color(0xFFFF6B6B), Color(0xFFFFD93D), Color(0xFF6BCB77),
                    Color(0xFF4D96FF), Color(0xFFB983FF)
                ).map { it.copy(alpha = 0.18f) }
            ),
            topLeft = Offset(0f, h * 0.60f),
            size = Size(w, h * 0.16f)
        )
        // 细碎光点
        for (i in 0 until 42) {
            val x = w * ((i * 37 % 100) / 100f)
            val y = h * ((i * 61 % 100) / 100f)
            drawCircle(Color.White.copy(alpha = 0.22f), radius = 1.4f, center = Offset(x, y))
        }
    }
}

@Composable
private fun GlassLabSection(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
    }
}

// 折射演示：彩色斜纹 + 网格被录制为素材层，玻璃球作为其兄弟节点采样，
// 因此拖动时折射、色散、模糊都会实时变化（不能自身采样自身）。
@Composable
private fun GlassLensPlayground(backdrop: Backdrop, modifier: Modifier = Modifier) {
    val playBackdrop = rememberLayerBackdrop()
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(268.dp)
            .clip(RoundedCornerShape(26.dp))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(26.dp))
    ) {
        val density = LocalDensity.current
        val ballSize = 104.dp
        val ballPx = with(density) { ballSize.toPx() }
        val maxX = with(density) { maxWidth.toPx() } - ballPx
        val maxY = with(density) { maxHeight.toPx() } - ballPx
        var pos by remember { mutableStateOf(Offset(maxX * 0.42f, maxY * 0.50f)) }
        LaunchedEffect(maxX, maxY) {
            pos = Offset(pos.x.coerceIn(0f, maxX), pos.y.coerceIn(0f, maxY))
        }

        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(playBackdrop)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.verticalGradient(listOf(Color(0xFF18233F), Color(0xFF0C1226)))
                )
                val stripes = listOf(
                    Color(0xFFFF6B6B), Color(0xFFFFD93D), Color(0xFF6BCB77),
                    Color(0xFF4D96FF), Color(0xFFB983FF)
                )
                val step = size.width / stripes.size.toFloat()
                stripes.forEachIndexed { index, color ->
                    val x = step * index + step * 0.5f
                    drawLine(
                        color = color.copy(alpha = 0.55f),
                        start = Offset(x, -size.height * 0.14f),
                        end = Offset(x - size.height * 0.55f, size.height * 1.14f),
                        strokeWidth = size.width * 0.075f,
                        cap = StrokeCap.Round
                    )
                }
                val grid = size.width / 12f
                var gx = 0f
                while (gx < size.width) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.10f),
                        start = Offset(gx, 0f),
                        end = Offset(gx, size.height),
                        strokeWidth = 1f
                    )
                    gx += grid
                }
                var gy = 0f
                while (gy < size.height) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.10f),
                        start = Offset(0f, gy),
                        end = Offset(size.width, gy),
                        strokeWidth = 1f
                    )
                    gy += grid
                }
            }
        }

        val demoSpec = GlassTuning.spec(GlassTuning.editing)
        Box(
            Modifier
                .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                .size(ballSize)
                .drawBackdrop(
                    backdrop = playBackdrop,
                    shape = { CircleShape },
                    effects = {
                        if (demoSpec.vibrancy) vibrancy()
                        if (demoSpec.blurDp > 0f) blur((demoSpec.blurDp * 0.6f).dp.toPx())
                        lens(
                            refractionHeight = (demoSpec.lensHeightDp * 1.6f).dp.toPx(),
                            refractionAmount = (demoSpec.lensAmountDp * 1.8f).dp.toPx(),
                            depthEffect = demoSpec.depth,
                            chromaticAberration = demoSpec.chromatic
                        )
                    },
                    highlight = {
                        if (demoSpec.highlight) Highlight.Default else Highlight.Plain.copy(alpha = 0f)
                    },
                    innerShadow = {
                        if (demoSpec.innerShadow) {
                            InnerShadow.Default
                        } else {
                            InnerShadow.Default.copy(alpha = 0f)
                        }
                    },
                    onDrawSurface = {
                        drawCircle(Color.White.copy(alpha = demoSpec.tintAlpha + 0.02f))
                        val hlCenter = Offset(size.width * 0.32f, size.height * 0.26f)
                        val hlRadius = size.width * 0.55f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                                center = hlCenter,
                                radius = hlRadius
                            ),
                            radius = hlRadius,
                            center = hlCenter
                        )
                    }
                )
                .pointerInput(maxX, maxY) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        pos = Offset(
                            (pos.x + dragAmount.x).coerceIn(0f, maxX),
                            (pos.y + dragAmount.y).coerceIn(0f, maxY)
                        )
                    }
                }
        )

        Text(
            text = "拖动玻璃球 · 折射与色散实时跟随上方参数",
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.72f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 12.dp)
        )
    }
}

// 组件预览：同一份 backdrop 下的玻璃按钮、开关行与分段控件
@Composable
private fun GlassComponentPreview(backdrop: Backdrop, modifier: Modifier = Modifier) {
    var taps by remember { mutableStateOf(0) }
    var switchOn by remember { mutableStateOf(true) }
    var segment by remember { mutableStateOf(1) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassSurface(
                accent = Color(0xFF60A5FA),
                onClick = { taps++ },
                backdrop = backdrop,
                modifier = Modifier
                    .weight(1f)
                    .height(78.dp)
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("玻璃按钮", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    Spacer(Modifier.height(2.dp))
                    Text("已按 $taps 次", fontSize = 11.sp, color = Color.White.copy(alpha = 0.60f))
                }
            }
            GlassSurface(
                accent = Color(0xFFA78BFA),
                onClick = { taps = 0 },
                backdrop = backdrop,
                modifier = Modifier
                    .weight(1f)
                    .height(78.dp),
                shape = Capsule()
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("胶囊表面", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    Spacer(Modifier.height(2.dp))
                    Text("轻点归零计数", fontSize = 11.sp, color = Color.White.copy(alpha = 0.60f))
                }
            }
        }
        GlassToggleRow(
            title = "玻璃开关",
            summary = "整行可点，轨道同样采样背后的极光",
            checked = switchOn,
            backdrop = backdrop,
            onChange = { switchOn = it }
        )
        SegmentedSlider(
            options = listOf("柔和", "标准", "浓烈"),
            selectedIndex = segment,
            onSelected = { segment = it },
            glass = true,
            backdrop = backdrop,
            selectedTextColor = Color.White,
            unselectedTextColor = Color.White.copy(alpha = 0.62f)
        )
    }
}

// 参数滑块：玻璃胶囊轨道 + 渐变进度 + 白色圆形滑块，支持点按定位与拖动
@Composable
private fun GlassTuningSlider(
    title: String,
    // 传 lambda 而不是数值：拖动时只在滑块自身作用域读状态，
    // 只重组这一个滑块，避免整页（4 个滑块 + 玻璃球 + 预览）逐帧重组造成卡死
    value: () -> Float,
    valueRange: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    backdrop: Backdrop,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    // 该组玻璃件没有绘制这项效果时置灰，避免“拖了没变化”
    enabled: Boolean = true
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it != 0f } ?: 1f
    val current = value()
    val progress = ((current - valueRange.start) / span).coerceIn(0f, 1f)
    // pointerInput 的 block 只在 key 变化时才会重启。切换调参分组后 spec 换成了另一个对象，
    // 若手势里仍持有旧回调就会写回上一组，表现为“只有第一次进入的那组能拖动”，
    // 因此这里始终读取最新的回调。
    val onChangeLatest = rememberUpdatedState(onChange)
    val onFinishedLatest = rememberUpdatedState(onFinished)
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 13.sp, color = Color.White.copy(alpha = 0.90f))
            Text(
                text = if (enabled) format(current) else "该组不适用",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.60f)
            )
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        val t = GlassTuning.track
                        if (t.vibrancy) vibrancy()
                        if (t.blurDp > 0f) blur(t.blurDp.dp.toPx())
                        lens(t.lensHeightDp.dp.toPx(), t.lensAmountDp.dp.toPx(), t.depth, t.chromatic)
                    },
                    highlight = {
                        if (GlassTuning.track.highlight) {
                            Highlight.Default
                        } else {
                            Highlight.Plain.copy(alpha = 0f)
                        }
                    },
                    innerShadow = {
                        if (GlassTuning.track.innerShadow) {
                            InnerShadow.Default
                        } else {
                            InnerShadow.Default.copy(alpha = 0f)
                        }
                    },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = GlassTuning.track.tintAlpha)) }
                )
                .border(1.dp, Color.White.copy(alpha = 0.16f), Capsule())
                .then(
                    if (enabled) {
                        Modifier.pointerInput(valueRange, span) {
                            awaitEachGesture {
                                val thumbPx = 26.dp.toPx()
                                // 触点映射到圆心行程：手指落在圆钮中心时数值不跳变，两端也能取满
                                val travelPx = (size.width - thumbPx).coerceAtLeast(1f)
                                fun progressAt(x: Float): Float =
                                    ((x - thumbPx / 2f) / travelPx).coerceIn(0f, 1f)
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    // Initial 通道：先于父级 verticalScroll 拿到事件并立即消费，
                                    // 拖动才不会被识别成页面滚动而中断
                                    pass = PointerEventPass.Initial
                                )
                                if (size.width > 0) {
                                    onChangeLatest.value(
                                        valueRange.start + span * progressAt(down.position.x)
                                    )
                                }
                                down.consume()
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val pressed = change.pressed
                                    if (pressed) {
                                        onChangeLatest.value(
                                            valueRange.start + span * progressAt(change.position.x)
                                        )
                                    }
                                    change.consume()
                                    if (!pressed) break
                                }
                                onFinishedLatest.value()
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            val thumbSize = 26.dp
            val travel = (maxWidth - thumbSize).coerceAtLeast(0.dp)
            // 进度条末端与圆钮圆心对齐，圆钮始终压在条头，不再出现“条长钮短”的错位
            val fillWidth = (travel * progress + thumbSize / 2f).coerceIn(0.dp, maxWidth)
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(fillWidth)
                    .height(36.dp)
                    .clip(Capsule())
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF60A5FA), Color(0xFFA78BFA))
                        )
                    )
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = travel * progress)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

// 开关行：玻璃圆角行 + 右侧玻璃开关，整行点击等同切换
@Composable
private fun GlassToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    backdrop: Backdrop,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    // 该组玻璃件没有绘制这项效果时置灰
    enabled: Boolean = true
) {
    val shape = RoundedRectangle(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    val b = GlassTuning.button
                    if (b.vibrancy) vibrancy()
                    if (b.blurDp > 0f) blur(b.blurDp.dp.toPx())
                    lens(
                        refractionHeight = b.lensHeightDp.dp.toPx(),
                        refractionAmount = b.lensAmountDp.dp.toPx(),
                        depthEffect = b.depth,
                        chromaticAberration = b.chromatic
                    )
                },
                highlight = {
                    if (GlassTuning.button.highlight) {
                        Highlight.Default
                    } else {
                        Highlight.Plain.copy(alpha = 0f)
                    }
                },
                innerShadow = {
                    if (GlassTuning.button.innerShadow) {
                        CardInnerShadow
                    } else {
                        CardInnerShadow.copy(alpha = 0f)
                    }
                },
                onDrawSurface = { drawRect(Color.White.copy(alpha = GlassTuning.button.tintAlpha)) }
            )
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .then(if (enabled) Modifier.clickable { onChange(!checked) } else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Color.White)
            Spacer(Modifier.height(2.dp))
            Text(summary, fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
        }
        GlassSwitch(
            checked = checked,
            onCheckedChange = { if (enabled) onChange(it) },
            glass = true,
            backdrop = backdrop
        )
    }
}

// 液态玻璃实验室：独立整页，参数实时驱动底栏与页内演示组件。
// 页面自建极光背景层，所有玻璃元素都是该层的兄弟节点，才能采样到真实内容。
@Composable
private fun LiquidGlassScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }
    val backdrop = rememberLayerBackdrop()
    val scrollState = rememberScrollState()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0F1E))
    ) {
        AuroraBackdrop(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        )
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(64.dp))

            // 先选要调的玻璃件，下面所有参数只作用于该组
            GlassLabSection("调参对象", "滑块 / 导轨 / 触控按钮各自独立，先选一组再调下面的参数")
            Spacer(Modifier.height(10.dp))
            SegmentedSlider(
                options = GlassGroup.entries.map { it.label },
                selectedIndex = GlassTuning.editing.ordinal,
                onSelected = { GlassTuning.editing = GlassGroup.entries[it] },
                glass = true,
                backdrop = backdrop,
                selectedTextColor = Color.White,
                unselectedTextColor = Color.White.copy(alpha = 0.62f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "当前：${GlassTuning.editing.label} —— ${GlassTuning.editing.desc}",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.58f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "该组没有绘制实现的参数会置灰并标注“该组不适用”",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.40f)
            )

            // 生效范围：决定玻璃应用到哪一类组件，两类可独立关闭
            Spacer(Modifier.height(20.dp))
            GlassLabSection("生效范围", "选择哪些组件应用液态玻璃，关闭后该类别回到普通实心样式")
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassToggleRow(
                    title = "卡片",
                    summary = "首页卡片、设置页卡片与开关行、子页面卡片",
                    checked = GlassTuning.scopeCard,
                    backdrop = backdrop,
                    onChange = { GlassTuning.scopeCard = it; GlassTuning.saveScope(prefs) }
                )
                GlassToggleRow(
                    title = "选择器",
                    summary = "底栏导轨与滑块、分段选择的轨道与滑块",
                    checked = GlassTuning.scopeSelector,
                    backdrop = backdrop,
                    onChange = { GlassTuning.scopeSelector = it; GlassTuning.saveScope(prefs) }
                )
            }

            // 预览紧跟调参对象：切换分组后立刻能看到这一组的效果
            Spacer(Modifier.height(20.dp))
            GlassLabSection("组件预览", "按钮、开关行与分段控件，分别采样三组参数")
            Spacer(Modifier.height(10.dp))
            GlassComponentPreview(backdrop = backdrop)

            Spacer(Modifier.height(20.dp))
            GlassLabSection("折射演示", "拖动玻璃球，观察条纹与网格被透镜弯折（跟随当前分组）")
            Spacer(Modifier.height(10.dp))
            GlassLensPlayground(backdrop = backdrop)

            Spacer(Modifier.height(20.dp))
            GlassLabSection("预设置", "一键切换性能与观感档位，会同时应用到三组")
            Spacer(Modifier.height(10.dp))
            SegmentedSlider(
                options = listOf("省电", "标准", "极致"),
                selectedIndex = GlassTuning.presetIndex,
                onSelected = { GlassTuning.applyPreset(it, prefs) },
                glass = true,
                backdrop = backdrop,
                selectedTextColor = Color.White,
                unselectedTextColor = Color.White.copy(alpha = 0.62f)
            )

            val spec = GlassTuning.spec(GlassTuning.editing)

            Spacer(Modifier.height(22.dp))
            GlassLabSection("背景透出", "背后内容透过玻璃的模糊程度与底色深浅")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GlassTuningSlider(
                    title = "模糊半径",
                    value = { spec.blurDp },
                    valueRange = 0f..24f,
                    format = { "${it.roundToInt()} dp" },
                    backdrop = backdrop,
                    onChange = { spec.blurDp = it },
                    onFinished = { GlassTuning.save(prefs) },
                    enabled = GlassTuning.editing.supports(GlassParam.BLUR)
                )
                GlassTuningSlider(
                    title = "着色浓度",
                    value = { spec.tintAlpha },
                    valueRange = 0f..0.2f,
                    format = { "${(it * 100).roundToInt()}%" },
                    backdrop = backdrop,
                    onChange = { spec.tintAlpha = it },
                    onFinished = { GlassTuning.save(prefs) },
                    enabled = GlassTuning.editing.supports(GlassParam.TINT)
                )
            }

            Spacer(Modifier.height(22.dp))
            GlassLabSection("折射与透镜", "边缘弯曲的量与方式，影响“玻璃厚度感”")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GlassTuningSlider(
                    title = "折射高度",
                    value = { spec.lensHeightDp },
                    valueRange = 0f..40f,
                    format = { "${it.roundToInt()} dp" },
                    backdrop = backdrop,
                    onChange = { spec.lensHeightDp = it },
                    onFinished = { GlassTuning.save(prefs) },
                    enabled = GlassTuning.editing.supports(GlassParam.LENS_HEIGHT)
                )
                GlassTuningSlider(
                    title = "折射强度",
                    value = { spec.lensAmountDp },
                    valueRange = 0f..48f,
                    format = { "${it.roundToInt()} dp" },
                    backdrop = backdrop,
                    onChange = { spec.lensAmountDp = it },
                    onFinished = { GlassTuning.save(prefs) },
                    enabled = GlassTuning.editing.supports(GlassParam.LENS_AMOUNT)
                )
                GlassToggleRow(
                    title = "深度折射",
                    summary = "按距边缘的距离模拟透镜厚度",
                    checked = spec.depth,
                    backdrop = backdrop,
                    onChange = { spec.depth = it; GlassTuning.save(prefs) },
                    enabled = GlassTuning.editing.supports(GlassParam.DEPTH)
                )
                GlassToggleRow(
                    title = "边缘色散",
                    summary = "透镜边缘分离出细微彩色光边",
                    checked = spec.chromatic,
                    backdrop = backdrop,
                    onChange = { spec.chromatic = it; GlassTuning.save(prefs) },
                    enabled = GlassTuning.editing.supports(GlassParam.CHROMATIC)
                )
            }

            Spacer(Modifier.height(22.dp))
            GlassLabSection("表面光效", "玻璃表面的高光、阴影与色彩饱和度")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassToggleRow(
                    title = "顶部高光",
                    summary = "玻璃上缘的镜面反射亮边",
                    checked = spec.highlight,
                    backdrop = backdrop,
                    onChange = { spec.highlight = it; GlassTuning.save(prefs) },
                    enabled = GlassTuning.editing.supports(GlassParam.HIGHLIGHT)
                )
                GlassToggleRow(
                    title = "内阴影",
                    summary = "内缘微弱阴影，增强厚度感",
                    checked = spec.innerShadow,
                    backdrop = backdrop,
                    onChange = { spec.innerShadow = it; GlassTuning.save(prefs) },
                    enabled = GlassTuning.editing.supports(GlassParam.INNER_SHADOW)
                )
                GlassToggleRow(
                    title = "鲜艳度增强",
                    summary = "提升采样区域的饱和度与对比",
                    checked = spec.vibrancy,
                    backdrop = backdrop,
                    onChange = { spec.vibrancy = it; GlassTuning.save(prefs) },
                    enabled = GlassTuning.editing.supports(GlassParam.VIBRANCY)
                )
            }

            Spacer(Modifier.height(22.dp))
            GlassLabSection("交互动效", "仅影响点按反馈：该组件的按压回弹")
            Spacer(Modifier.height(12.dp))
            GlassToggleRow(
                title = "弹性动效",
                summary = "按压与选择时带弹性回弹",
                checked = spec.bouncy,
                backdrop = backdrop,
                onChange = { spec.bouncy = it; GlassTuning.save(prefs) },
                enabled = GlassTuning.editing.supports(GlassParam.BOUNCY)
            )

            Spacer(Modifier.height(18.dp))
            GlassSurface(
                accent = Color(0xFF60A5FA),
                onClick = { GlassTuning.applyPreset(1, prefs) },
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedRectangle(16.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("三组恢复标准参数", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "玻璃折射 / 色散 / 高光由 Apache-2.0 许可的 backdrop 与 shapes 提供，" +
                    "页面仅调用其公开 API；视觉与参数组织为本项目独立实现。",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.42f)
            )
            Spacer(Modifier.height(28.dp))
        }

        // 顶部渐变遮罩：保证浅色主题下标题依旧可读
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .height(120.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xE60A0F1E), Color(0x000A0F1E))
                    )
                )
        )
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
            Text("液态玻璃实验室", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}
