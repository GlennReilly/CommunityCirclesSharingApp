// Each module applies the plugins it needs. They are declared here (without applying them) so Gradle loads the Kotlin
// plugin once for the whole build; with several Kotlin modules it otherwise loads it once per module and the
// Kotlin/JS tooling clashes ("Cannot add extension with name 'kotlinNodeJs'").
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.shadow) apply false
}
