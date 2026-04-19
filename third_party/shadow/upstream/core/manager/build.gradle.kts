// Plugin manager — install/copy/odex bookkeeping + SQLite-backed registry
// of installed plugins. Vendored from Shadow `core/manager`. Android library
// because of SQLiteOpenHelper / Context / ContentValues.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tencent.shadow.core.manager"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":shadow-utils"))
    compileOnly(project(":shadow-common"))
    api(project(":shadow-load-parameters"))
}
