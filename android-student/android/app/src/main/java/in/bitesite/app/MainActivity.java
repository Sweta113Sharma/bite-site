package in.bitesite.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

/**
 * The remote-URL shell around app.bitesite.in.
 *
 * <p>Capacitor's default bridge behaviour handles everything this shell needs:
 * the remote server URL stays inside the WebView, while any external URL (e.g.
 * Razorpay UPI app-switch intents) is handed off to the system via
 * {@code Bridge.launchIntent}. Back-button history navigation is handled by the
 * bridge too, so no custom WebViewClient is required here.
 *
 * <p>The one addition is {@link SplashOverlay}, which draws the animated launch
 * splash over the WebView while the remote page loads. See that class for why the
 * Capacitor SplashScreen plugin cannot do this job.
 */
public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // super.onCreate returns early without building a bridge if the device has no
        // usable WebView; in that case it has shown the no_webview layout and a splash
        // over the top of it would only hide the explanation.
        if (bridge == null) return;
        SplashOverlay overlay = SplashOverlay.install(this);
        if (overlay != null) overlay.dismissWhenReady(bridge.getWebView());
    }
}
