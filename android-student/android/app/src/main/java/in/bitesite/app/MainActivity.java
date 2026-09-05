package in.bitesite.app;

import com.getcapacitor.BridgeActivity;

/**
 * The remote-URL shell around bitesite-app.azurewebsites.net.
 *
 * <p>Capacitor's default bridge behaviour handles everything this shell needs:
 * the remote server URL stays inside the WebView, while any external URL (e.g.
 * Razorpay UPI app-switch intents) is handed off to the system via
 * {@code Bridge.launchIntent}. Back-button history navigation is handled by the
 * bridge too, so no custom WebViewClient is required here.
 */
public class MainActivity extends BridgeActivity {
}
