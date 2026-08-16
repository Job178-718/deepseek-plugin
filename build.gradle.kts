plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.example.deepseek"
version = "1.0.11"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // 使用本地 Android Studio 作为 IntelliJ 平台 SDK
    intellijPlatform {
        local("C:/soft/as")
    }

    // 直接把 gson 打进插件，避免依赖 IDE 内部类加载器
    implementation("com.google.code.gson:gson:2.10.1")

    // Markdown 渲染（commonmark + GFM 表格/删除线扩展）
    implementation("org.commonmark:commonmark:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.21.0")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.example.deepseek"
        name = "DeepSeek Assistant"
        version = "1.0.10"
        description = """
            <h3>DeepSeek AI Assistant for Android Studio</h3>
            <p>在 Android Studio 中直接使用 DeepSeek 大模型：</p>
            <ul>
                <li>侧边栏聊天面板</li>
                <li>选中代码右键：解释 / 优化 / 加注释</li>
            </ul>
            <p>API Key 通过环境变量 <code>DEEPSEEK_API_KEY</code> 提供。</p>
        """.trimIndent()

        vendor {
            name = "local-dev"
        }

        ideaVersion {
            sinceBuild = "253"
            untilBuild = "253.*"
        }
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }

    signPlugin {
        enabled = false
    }
}
