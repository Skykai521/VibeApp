// Tiny shared JVM module that replaces Shadow's upstream
// `coding/java-build-config`. PluginContainerActivity.java and a couple
// of loader files reference `com.tencent.shadow.coding.java_build_config.BuildConfig`;
// having two modules ship their own copy causes a duplicate-class error
// when the loader APK merges both. Extract into one place.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
