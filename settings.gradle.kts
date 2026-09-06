rootProject.name = "icebergg-cs3-german-repo"

// Bindet automatisch jedes Unterverzeichnis ein, das eine eigene
// `build.gradle.kts` enthält. Neue Provider sind damit nur ein neuer Ordner.
File(rootDir, ".").eachDir { dir ->
    if (File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
