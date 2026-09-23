rootProject.name = "ReSync"
apply(from = file("../Rebase/gradle/restudio-workspace.settings.gradle"))
include("ReSyncCore")
include("ReSyncVelocity")
