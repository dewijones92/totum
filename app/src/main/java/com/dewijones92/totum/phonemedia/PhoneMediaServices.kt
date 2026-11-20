package com.dewijones92.totum.phonemedia

import android.content.Context
import com.dewijones92.totum.data.phone.ImportedPhoneMedia
import com.dewijones92.totum.data.phone.PhoneMediaSource
import com.dewijones92.totum.data.phone.google.BrowserOpener
import com.dewijones92.totum.data.phone.google.GoogleOAuthClient
import com.dewijones92.totum.data.phone.google.GooglePhotosConfig
import com.dewijones92.totum.data.phone.google.GooglePhotosPicker
import com.dewijones92.totum.data.phone.google.GoogleSignIn
import com.dewijones92.totum.data.phone.google.GoogleSignInPort
import com.dewijones92.totum.data.phone.google.GoogleTokenStore
import com.dewijones92.totum.data.phone.google.InMemoryGoogleTokenStore
import com.dewijones92.totum.data.phone.google.PhotosPickerApi
import com.dewijones92.totum.operations.Operations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File

class PhoneMediaServices(
    val device: PhoneMediaSource,
    val imports: ImportedPhoneMedia,
    val permissions: PhoneMediaPermissionState,
    val picks: GooglePhotosPicks,
    val screenPictures: ScreenPictures = ScreenPictures.AsTheyAre,
) {
    companion object {
        fun build(
            context: Context,
            http: OkHttpClient,
            operations: Operations,
            scope: CoroutineScope,
            config: GooglePhotosConfig,
        ): PhoneMediaServices {
            val imports = ImportedPhoneMedia(File(context.filesDir, "google-photos"))
            val browser = AndroidBrowserOpener(context)
            val oauth = GoogleOAuthClient(http, config, SharedPrefsGoogleTokenStore(context))
            val picker = pickerOf(
                config,
                oauth,
                LoopbackGoogleSignIn(config, oauth, browser),
                http,
                imports,
                browser,
            )
            return PhoneMediaServices(
                device = MediaStorePhoneSource(context),
                imports = imports,
                permissions = PhoneMediaPermissionState.of(context),
                picks = GooglePhotosPicks(picker, operations, scope),
                screenPictures = AndroidScreenPictures(context),
            )
        }

        fun inMemory(
            device: PhoneMediaSource,
            imports: ImportedPhoneMedia,
            config: GooglePhotosConfig = GooglePhotosConfig.NOT_SET_UP,
            tokens: GoogleTokenStore = InMemoryGoogleTokenStore(),
            permissions: PhoneMediaPermissionState = PhoneMediaPermissionState.everythingGranted(),
        ): PhoneMediaServices {
            val http = OkHttpClient()
            val oauth = GoogleOAuthClient(http, config, tokens)
            val picker = pickerOf(
                config,
                oauth,
                GoogleSignInPort { GoogleSignIn.Failed("no sign-in here") },
                http,
                imports,
                BrowserOpener { false },
            )
            return PhoneMediaServices(
                device = device,
                imports = imports,
                permissions = permissions,
                picks = GooglePhotosPicks(picker, Operations(), CoroutineScope(SupervisorJob())),
            )
        }

        @Suppress("LongParameterList")
        private fun pickerOf(
            config: GooglePhotosConfig,
            oauth: GoogleOAuthClient,
            signIn: GoogleSignInPort,
            http: OkHttpClient,
            imports: ImportedPhoneMedia,
            browser: BrowserOpener,
        ): GooglePhotosPicker {
            val api = PhotosPickerApi(http, token = { oauth.accessToken() }, refreshToken = { oauth.forceRefresh() })
            return GooglePhotosPicker(config, oauth, signIn, api, imports, browser)
        }
    }
}
