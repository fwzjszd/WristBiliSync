plugins {
    id("com.android.application") version "9.2.1" apply false
    // AGP 9.0+ 内置 Kotlin（9.2 内置 KGP 2.3.10），不再需要 org.jetbrains.kotlin.android
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
}
