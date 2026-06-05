rootProject.name = "CloudstreamPlugins"

// This file sets what projects are included. All new projects should get automatically included unless specified in "disabled" variable.
val disabled = listOf(
    "3isk",
    "Aia2tv",
    "Aia2tv 2",
    "Akwam",
    "Anim3rb",
    "Anime4up",
    "Animeiat",
    "Animerco",
    "Animewitcher",
    "Arabseed",
    "Bristege",
    "Cee",
    "CimaClub",
    "Cimalight",
    "CimaNowProvider",
    "Cimatn",
    "Cimawbas",
    "cinemana",
    "dima-toon",
    "Egydead",
    "eseek",
    "Faselhd",
    "Krmzy",
    "Lodynet",
    "MyCimaProvider",
    "Ohatv",
    "Replaymatch",
    "Shahid4u",
    "Syria-live",
    "Topcinema",
    "TukTukcima",
    "Tuniflix",
    "TVgarden",
    "Viu",
    "Wecima",
    "Witanime",
    "Youtube",
    "repo",
    "build",
    "gradle",
    "import",
    "docs"
)

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it)}
}

// To only include a single project, comment out the previous lines (except the first one), and include your plugin like so:
// include("PluginName")
