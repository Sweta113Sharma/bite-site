package in.bitesite.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The launch splash: a bone field the brand elements pop into.
 *
 * <p>Why this exists at all. Android 12 removed custom launch images — the system splash
 * takes a solid colour and one centre icon masked to a circle, and nothing else, so the
 * illustrated splash.png could never be shown that way. The theme therefore paints the
 * same bone as the first frame with a transparent icon, and this view draws the artwork
 * on top of it, in pieces, so the elements can animate in.
 *
 * <p>This does NOT use the Capacitor SplashScreen plugin's launch path. That path holds
 * the system splash by installing an OnPreDrawListener on android.R.id.content which
 * returns false, blocking every draw underneath it — an overlay added there would stay
 * invisible until it released. capacitor.config.json switches that path off
 * (launchShowDuration 0) so this view owns the launch visual outright.
 *
 * <p><b>Everything here is positioned with scale and translation, never with layout.</b>
 * Two earlier attempts set each child's LayoutParams from inside onSizeChanged. A
 * requestLayout issued during layout is deferred, and the second pass either arrived
 * after the animation had already started — so it played at 1x1 and was invisible — or
 * did not arrive at all. Scale and translation are draw-time properties: they take effect
 * on the next frame with no measure or layout pass, so there is no ordering to lose.
 */
public class SplashOverlay extends FrameLayout {

    /** The artwork's own canvas, from splash.png's non-background bounds. */
    private static final float ART_W = 1268f;
    private static final float ART_H = 2732f;

    private static final int BONE = 0xFFF2EAD9;

    /** Hold past the intro so the animation is never cut off mid-pop. */
    private static final long MIN_VISIBLE_MS = 950L;
    /** Never strand the user behind the splash if the page never reports done. */
    private static final long MAX_VISIBLE_MS = 8000L;
    private static final long POLL_MS = 80L;
    private static final long POP_MS = 430L;

    /** cx/cy/w are fractions of the artwork canvas; delay staggers the pop. */
    private static final class Element {
        final int res; final float cx, cy, w, fromScale, outX, outY; final long delay;
        Element(int res, float cx, float cy, float w, float fromScale, float outX, float outY, long delay) {
            this.res = res; this.cx = cx; this.cy = cy; this.w = w;
            this.fromScale = fromScale; this.outX = outX; this.outY = outY; this.delay = delay;
        }
    }

    // Geometry measured off splash.png, so the composition matches the original exactly.
    // The corner pieces start pushed further into their own corner and settle inward,
    // which reads as them arriving rather than merely growing.
    private static final Element[] ELEMENTS = {
        new Element(R.drawable.splash_logo,   0.5414f, 0.4466f, 0.6459f, 0.30f,  0f,     0f,     60L),
        new Element(R.drawable.splash_pizza,  0.1897f, 0.1444f, 0.3793f, 0.55f, -0.18f, -0.18f, 200L),
        new Element(R.drawable.splash_cup,    0.8880f, 0.1960f, 0.2240f, 0.55f,  0.18f, -0.18f, 265L),
        new Element(R.drawable.splash_fries,  0.1916f, 0.8031f, 0.3833f, 0.55f, -0.18f,  0.18f, 330L),
        new Element(R.drawable.splash_burger, 0.7701f, 0.8486f, 0.4598f, 0.55f,  0.18f,  0.18f, 395L),
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageView[] views = new ImageView[ELEMENTS.length];
    private long shownAt;
    private boolean introStarted;
    private boolean dismissing;
    private WebView webView;

    private SplashOverlay(Context context) {
        super(context);
        setBackgroundColor(BONE);
        // Swallow taps: the WebView underneath is live and must not receive them.
        setClickable(true);
        for (int i = 0; i < ELEMENTS.length; i++) {
            ImageView v = new ImageView(context);
            v.setImageResource(ELEMENTS[i].res);
            v.setAlpha(0f);
            views[i] = v;
            // Centred and intrinsically sized. The first ordinary layout pass gives every
            // child its natural size; scale and translation do the rest.
            addView(v, new LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
    }

    /** Adds the overlay above the activity's content. Returns null if there is nowhere to put it. */
    static SplashOverlay install(Activity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return null;
        SplashOverlay overlay = new SplashOverlay(activity);
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return overlay;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // Children now carry their intrinsic size, which is all the maths below needs.
        if (!introStarted && getWidth() > 0 && views[0].getWidth() > 0) {
            introStarted = true;
            playIntro(getWidth(), getHeight());
        }
    }

    /**
     * Cover-maps the artwork canvas onto the screen, the same way a centre-cropped splash
     * image would sit, then drives each element there with scale and translation. Scaling
     * by the larger ratio keeps the corner blobs bleeding off the edges on any aspect
     * ratio instead of leaving bone gaps at the top and bottom.
     */
    private void playIntro(int screenW, int screenH) {
        float cover = Math.max(screenW / ART_W, screenH / ART_H);
        float drawW = ART_W * cover, drawH = ART_H * cover;
        float offX = (screenW - drawW) / 2f, offY = (screenH - drawH) / 2f;

        shownAt = SystemClock.uptimeMillis();

        for (int i = 0; i < ELEMENTS.length; i++) {
            Element e = ELEMENTS[i];
            ImageView v = views[i];

            // Uniform: the element PNGs were cut at the artwork's own aspect ratio, so
            // width and height resolve to the same scale factor.
            float target = (e.w * drawW) / v.getWidth();
            // The child is centred, so translation is the offset from the screen centre
            // to where this element belongs.
            float tx = (offX + e.cx * drawW) - screenW / 2f;
            float ty = (offY + e.cy * drawH) - screenH / 2f;
            float span = e.w * drawW;

            v.setScaleX(target * e.fromScale);
            v.setScaleY(target * e.fromScale);
            v.setTranslationX(tx + e.outX * span);
            v.setTranslationY(ty + e.outY * span);

            v.animate()
                .scaleX(target).scaleY(target)
                .translationX(tx).translationY(ty)
                .alpha(1f)
                .setStartDelay(e.delay)
                .setDuration(POP_MS)
                .setInterpolator(new OvershootInterpolator(2.2f))
                .start();
        }
    }

    /**
     * Holds the splash until the page behind it is actually painted, then fades out.
     *
     * <p>Polls the WebView's progress rather than installing a WebViewClient or
     * WebChromeClient: replacing either of those would displace Capacitor's own, which
     * handles the file chooser, geolocation prompts and external-URL hand-off.
     */
    void dismissWhenReady(WebView webView) {
        this.webView = webView;
        handler.postDelayed(poll, POLL_MS);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (dismissing) return;
            // Intro has not begun, so there is nothing to time from yet.
            if (shownAt == 0L) {
                handler.postDelayed(this, POLL_MS);
                return;
            }
            long elapsed = SystemClock.uptimeMillis() - shownAt;
            boolean ready = webView != null && webView.getProgress() >= 100;
            if (elapsed >= MAX_VISIBLE_MS || (ready && elapsed >= MIN_VISIBLE_MS)) {
                dismiss();
            } else {
                handler.postDelayed(this, POLL_MS);
            }
        }
    };

    private void dismiss() {
        if (dismissing) return;
        dismissing = true;
        handler.removeCallbacks(poll);
        animate()
            .alpha(0f)
            .setDuration(280L)
            .setInterpolator(new DecelerateInterpolator())
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    ViewGroup parent = (ViewGroup) getParent();
                    if (parent != null) parent.removeView(SplashOverlay.this);
                }
            })
            .start();
    }
}
