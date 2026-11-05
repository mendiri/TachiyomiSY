package eu.kanade.tachiyomi.data.updater

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import exh.syDebugVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.release.interactor.GetApplicationRelease

@Inject
class AppUpdateChecker(
    private val getApplicationRelease: GetApplicationRelease,
) {

    suspend fun checkForUpdate(forceCheck: Boolean = false): GetApplicationRelease.Result {
        // Disable app update checks for older Android versions that we're going to drop support for
        // if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        //     return GetApplicationRelease.Result.OsTooOld
        // }

        return withContext(Dispatchers.IO) {
            if (isForkBuild) {
                // Releases of the fork are tagged with the number of their CI build, like "r42"
                return@withContext getApplicationRelease.await(
                    GetApplicationRelease.Arguments(
                        isPreview = true,
                        commitCount = 0,
                        versionName = BuildConfig.VERSION_NAME,
                        repository = GITHUB_REPO,
                        syDebugVersion = BuildConfig.FORK_BUILD,
                        forceCheck = forceCheck,
                    ),
                )
            }
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    // SY -->
                    isPreviewBuildType,
                    // SY <--
                    BuildConfig.COMMIT_COUNT.toInt(),
                    BuildConfig.VERSION_NAME,
                    GITHUB_REPO,
                    // SY -->
                    syDebugVersion,
                    // SY <--
                    forceCheck,
                ),
            )

            result
        }
    }
}

/** Debug build made by the fork's CI, which updates itself from the fork's releases. */
val isForkBuild: Boolean
    get() = BuildConfig.BUILD_TYPE == "debug" && BuildConfig.FORK_BUILD != "0"

const val FORK_GITHUB_REPO = "mendiri/TachiyomiSY"

val GITHUB_REPO: String by lazy {
    // SY -->
    if (isForkBuild) {
        FORK_GITHUB_REPO
    } else if (isPreviewBuildType) {
        "jobobby04/TachiyomiSYPreview"
    } else {
        "jobobby04/tachiyomiSY"
    }
    // SY <--
}
