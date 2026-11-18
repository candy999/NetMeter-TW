package vip.mystery0.pixel.meter.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.PreferenceCategory
import vip.mystery0.pixel.meter.BuildConfig
import vip.mystery0.pixel.meter.R

private const val REPOSITORY_URL = "https://github.com/candy999/NetMeter-TW"
private const val UPSTREAM_REPOSITORY_URL = "https://github.com/Pixel-Tailor-CN/PixelMeter"

@Composable
fun AboutSettingsSection() {
    val uriHandler = LocalUriHandler.current

    PreferenceCategory(title = { Text(stringResource(R.string.settings_category_about)) })
    Preference(
        title = { Text(stringResource(R.string.settings_app_version)) },
        summary = { Text(BuildConfig.VERSION_NAME) }
    )
    Preference(
        title = { Text(stringResource(R.string.settings_github)) },
        summary = { Text(REPOSITORY_URL) },
        onClick = { uriHandler.openUri(REPOSITORY_URL) }
    )
    // Apache-2.0 attribution for the upstream project this fork is based on.
    Preference(
        title = { Text(stringResource(R.string.settings_original_project_title)) },
        summary = { Text(stringResource(R.string.settings_original_project_desc)) },
        onClick = { uriHandler.openUri(UPSTREAM_REPOSITORY_URL) }
    )
}
