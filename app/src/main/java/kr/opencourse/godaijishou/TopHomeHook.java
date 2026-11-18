package kr.opencourse.godaijishou;

import android.content.Context;

import java.util.Collections;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * LG's home is always the folded phone's home, never the swivel home (scope: com.lge.launcher3).
 * Not in SystemUI: told "closed", it drops the second screen's gesture watchers.
 *
 * With the swivel open LG's home switches to the swivel home (the clock and app wheel made for
 * the top screen turned sideways). It learns the swivel state from LG's PostureManager and
 * DisplayManagerHelper only: getSwivelState() (2 = open) and state callbacks (100/101
 * opening/open, 200/201 closing/closed), the first of which LG's service sends right when the
 * callback registers. Here getSwivelState() always answers "closed" and every "opening/open"
 * reaching a callback arrives as "closing/closed". The second screen runs its own launcher
 * (com.lge.secondlauncher) and is not affected; nor is the system server (displays, rotation,
 * power).
 */
final class TopHomeHook {

    static final String TARGET_PACKAGE = "com.lge.launcher3";
    static final String HOOK_NAME = "lghome";
    /** Settings.Global flag, 1 while the top screen is shown on the second screen. */
    static final String KEY_TOP_VIEW = "godaijishou_top_view";
    /** Settings.Global, "scale,left,top,width,height": the top screen on the second one. */
    static final String KEY_TOP_MAP = "godaijishou_top_view_map";

    private static final String POSTURE = "com.lge.systemservice.core.PostureManager";
    private static final String DISPLAY_HELPER = "com.lge.display.DisplayManagerHelper";
    private static final int STATE_OPEN = 2;
    private static final int STATE_CLOSED = 0;
    private static final int OPENING = 100, OPENED = 101, CLOSING = 200, CLOSED = 201;

    // Callback classes whose onSwivelStateChanged is already rewritten.
    private static final Set<Class<?>> hookedCallbacks = Collections.synchronizedSet(new java.util.HashSet<>());

    private TopHomeHook() {
    }

    static void install(ClassLoader cl) {
        safely(() -> installReport(cl));
        installGridOnce(cl);
        installSecondScreenGestures(cl);
        safely(() -> installLabelsBelow(cl));
        safely(() -> installFullIcons(cl));
        safely(() -> installFreshLayout(cl));
        safely(() -> installNoZoomRestart(cl));
        for (String name : new String[] {POSTURE, DISPLAY_HELPER}) {
            Class<?> c = XposedHelpers.findClassIfExists(name, cl);
            if (c == null) continue;
            try {
                hookSource(c);
            } catch (Throwable t) {
                ModuleLog.w(t);
            }
        }
    }

    private static void hookSource(Class<?> source) {
        XposedBridge.hookAllMethods(source, "getSwivelState", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Integer.valueOf(STATE_OPEN).equals(param.getResult())) param.setResult(STATE_CLOSED);
            }
        });
        // Every callback handed in gets its "open" events turned into "closed" ones.
        XC_MethodHook register = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] != null) folded(param.args[0].getClass());
            }
        };
        XposedBridge.hookAllMethods(source, "registerPostureStateCallback", register);
        XposedBridge.hookAllMethods(source, "registerSwivelStateCallback", register);
    }

    // The main display's panel, whichever way it is turned: {short side, long side, dpi}.
    // A rotation alone is not a new panel (LG's home handles rotation itself).
    private static int[] panel(Context c) {
        int[] m = mainDisplay(c);
        return m == null ? null : new int[] {Math.min(m[0], m[1]), Math.max(m[0], m[1]), m[2]};
    }

    // --- Second screen gestures after a switch ---

    private static final String TOUCH_SERVICE = "com.android.quickstep.TouchInteractionService";
    // The last second-screen gesture monitor setup failed (display 4 off at the time).
    private static volatile boolean monitorFailed;

    /**
     * Quickstep watches the second screen's gestures (home, recents) through an input monitor
     * on display 4, set up at start and on swivel events. Main screen mode removes display 4
     * (its monitor goes with it) and adds it back on the way out, with no swivel event: the
     * second screen came back without gestures. Display 4 added again: set the monitor up again
     * (LG's own setup, which drops a stale one first).
     */
    private static void installSecondScreenGestures(ClassLoader cl) {
        Class<?> service = XposedHelpers.findClassIfExists(TOUCH_SERVICE, cl);
        if (service == null) return;
        // Started while swapped, display 4 is the top panel, off, and takes no gesture monitor:
        // LG's own setup threw and took the home down in a loop. No monitor there then; the
        // display listener below sets it up when display 4 is a usable screen again.
        XposedBridge.hookAllMethods(service, "initInputMonitorForMulti", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    monitorFailed = false;
                } catch (java.lang.reflect.InvocationTargetException e) {
                    monitorFailed = true;
                    if (!(e.getCause() instanceof IllegalArgumentException)) ModuleLog.w(e);
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
                param.setResult(null);
            }
        });
        XposedBridge.hookAllMethods(service, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object s = param.thisObject;
                try {
                    android.hardware.display.DisplayManager dm = ((Context) s)
                            .getSystemService(android.hardware.display.DisplayManager.class);
                    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    dm.registerDisplayListener(new android.hardware.display.DisplayManager.DisplayListener() {
                        @Override
                        public void onDisplayAdded(int displayId) {
                            if (displayId != MainModule.SECOND_SCREEN_DISPLAY_ID) return;
                            try {
                                XposedHelpers.callMethod(s, "initInputMonitorForMulti");
                            } catch (Throwable t) {
                                ModuleLog.w(t);
                            }
                        }

                        @Override
                        public void onDisplayRemoved(int displayId) {
                        }

                        @Override
                        public void onDisplayChanged(int displayId) {
                            // Only after a failed setup (started swapped): once display 4 is
                            // a usable screen again, set the monitor up.
                            if (displayId != MainModule.SECOND_SCREEN_DISPLAY_ID || !monitorFailed) return;
                            try {
                                XposedHelpers.callMethod(s, "initInputMonitorForMulti");
                            } catch (Throwable t) {
                                ModuleLog.w(t);
                            }
                        }
                    }, main);
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        });
    }

    // Translation that centers the dot row 0.8 of its own height above the bar's top edge.
    private static int dotsAboveBar(android.view.View bar, android.view.View dots) {
        int center = bar.getTop() - Math.round(dots.getHeight() * 0.8f);
        return center - (dots.getTop() + dots.getHeight() / 2);
    }

    /**
     * Tells the app (and through it system_server) that LG's home is hooked this boot: main
     * screen mode needs it and falls back to second screen mode without it.
     *
     * Application.onCreate alone was not enough: it is an empty framework method, skipped when
     * the home's Application doesn't call super, or when its compiled code inlined that empty
     * call (LG Home 9.70.22 never reported). The home's own Launcher.onCreate/onResume have a
     * body of their own and run every time the home is shown, so a report refused early in
     * the boot goes through on a later one. Application.onCreate stays as it was, as a backup.
     */
    private static void installReport(ClassLoader cl) {
        XC_MethodHook onThis = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                HookReport.report((Context) param.thisObject, HOOK_NAME);
            }
        };
        Class<?> launcher = XposedHelpers.findClassIfExists("com.android.launcher3.Launcher", cl);
        if (launcher != null) {
            safely(() -> XposedBridge.hookAllMethods(launcher, "onCreate", onThis));
            safely(() -> XposedBridge.hookAllMethods(launcher, "onResume", onThis));
        }
        safely(() -> XposedBridge.hookAllMethods(android.app.Application.class, "onCreate", onThis));
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    // --- Labels under the icons in landscape ---

    private static final String PKG = "com.lge.launcher3";

    /**
     * On the Wing (swivel home feature) LG's home puts each label beside its icon in landscape,
     * in cells sized for that: on the second screen's panel as the main display every name
     * wrapped into two short lines. Landscape is laid out like portrait here: labels under the
     * icons and cells sized for icon + label. Only the home's own icons (workspace, dock,
     * folders, app drawer); the long-press menus keep their side-by-side rows.
     */
    private static void installLabelsBelow(ClassLoader cl) {
        Class<?> profile = XposedHelpers.findClass("com.android.launcher3.DeviceProfile", cl);
        // Read in the constructor (updateIconSize: cell and text sizes) and later (padding).
        XC_MethodHook portraitCells = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                XposedHelpers.setBooleanField(param.thisObject, "cellLayoutHorizontal", false);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                XposedHelpers.setBooleanField(param.thisObject, "cellLayoutHorizontal", false);
            }
        };
        XposedBridge.hookAllMethods(profile, "updateIconSize", portraitCells);
        XposedBridge.hookAllConstructors(profile, portraitCells);
        // Portrait-style cells let the dock icons grow past the landscape dock's height (cut
        // off at the bottom). The dock draws its icons at hotseatIconSizePx / iconSizePx; kept
        // to what the dock can show. Home screen icons keep their size.
        XC_MethodHook dockFits = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object p = param.thisObject;
                if (!XposedHelpers.getBooleanField(p, "isLandscape")) return;
                int bar = XposedHelpers.getIntField(p, "hotseatBarHeightPx");
                int dock = XposedHelpers.getIntField(p, "hotseatIconSizePx");
                int max = Math.round(bar * 0.8f);
                if (bar <= 0 || dock <= max) return;
                XposedHelpers.setIntField(p, "hotseatIconSizePx", max);
                XposedHelpers.setIntField(p, "hotseatCellWidthPx", max);
                XposedHelpers.setIntField(p, "hotseatCellHeightPx", max);
            }
        };
        XposedBridge.hookAllMethods(profile, "updateIconSize", dockFits);
        // LG's profile sets the dock icon size again after the base constructor.
        Class<?> lgProfile = XposedHelpers.findClassIfExists("com.lge.launcher3.profile.LGDeviceProfile", cl);
        if (lgProfile != null) XposedBridge.hookAllConstructors(lgProfile, dockFits);
        // An open folder in landscape: LG's rows (folderCellHeightPxForSwivel, about 238 px,
        // four of them) are taller than the folder's content area, which centered them and
        // pushed the first row's icons up under the title. The rows share the content area
        // instead, and each folder icon shrinks to what its row leaves above a one-line label.
        XC_MethodHook folderRows = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object p = param.thisObject;
                if (!XposedHelpers.getBooleanField(p, "isLandscape")) return;
                // LG uses one pair or the other (carousel layout or not): both share the area.
                int row = folderRowHeight(p, "numFolderRowsForSwivel");
                if (row > 0) XposedHelpers.setIntField(p, "folderCellHeightPxForSwivel", row);
                row = folderRowHeight(p, "numFolderRows");
                if (row > 0) XposedHelpers.setIntField(p, "folderCellHeightPx", row);
            }
        };
        XposedBridge.hookAllConstructors(profile, folderRows);
        XposedBridge.hookAllMethods(profile, "updateIconSize", folderRows);
        if (lgProfile != null) XposedBridge.hookAllConstructors(lgProfile, folderRows);
        Class<?> folderPages = XposedHelpers.findClassIfExists("com.android.launcher3.folder.FolderPagedView", cl);
        if (folderPages != null) {
            XposedBridge.hookAllMethods(folderPages, "createNewView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.getResult() instanceof android.widget.TextView)) return;
                    android.widget.TextView t = (android.widget.TextView) param.getResult();
                    Object profile = XposedHelpers.callMethod(t.getContext(), "getDeviceProfile");
                    if (!XposedHelpers.getBooleanField(profile, "isLandscape")) return;
                    int row = Math.min(XposedHelpers.getIntField(profile, "folderCellHeightPxForSwivel"),
                            XposedHelpers.getIntField(profile, "folderCellHeightPx"));
                    android.graphics.drawable.Drawable icon = t.getCompoundDrawables()[1];
                    if (icon == null || row <= 0) return;
                    t.setMaxLines(1);
                    int room = row - t.getPaddingTop() - t.getPaddingBottom()
                            - t.getCompoundDrawablePadding() - Math.round(t.getLineHeight() * 1.3f);
                    if (room <= 0) return;
                    // Icons arriving later (the icon cache) are set at full size again.
                    XposedHelpers.setAdditionalInstanceField(t, "folderIconSize", room);
                    if (icon.getBounds().width() <= room) return;
                    icon.setBounds(0, 0, room, room);
                    t.setCompoundDrawables(null, icon, null, null);
                }
            });
            Class<?> bubble = XposedHelpers.findClassIfExists("com.android.launcher3.BubbleTextView", cl);
            if (bubble != null) XposedBridge.hookAllMethods(bubble, "setIcon", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object size = XposedHelpers.getAdditionalInstanceField(param.thisObject, "folderIconSize");
                    if (size == null || param.args.length < 2 || !(param.args[1] instanceof Integer)) return;
                    int s = (Integer) size, own = (Integer) param.args[1];
                    if (own == -1 || own > s) param.args[1] = s;
                }
            });
        }
        // The open folder sits on the bottom edge in landscape (the swivel top screen's place
        // for it), leaving the panel's top empty: centered between the status and nav bars.
        Class<?> folderView = XposedHelpers.findClassIfExists("com.android.launcher3.folder.Folder", cl);
        if (folderView != null) {
            XposedBridge.hookAllMethods(folderView, "centerAboutIcon", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    android.view.View f = (android.view.View) param.thisObject;
                    if (f.getResources().getConfiguration().orientation
                            != android.content.res.Configuration.ORIENTATION_LANDSCAPE) return;
                    Object lp = f.getLayoutParams();
                    if (!(f.getParent() instanceof android.view.View) || lp == null) return;
                    android.content.res.Resources sys = android.content.res.Resources.getSystem();
                    int top = dimen(sys, "status_bar_height"), nav = dimen(sys, "navigation_bar_height");
                    int h = XposedHelpers.getIntField(lp, "height");
                    int avail = ((android.view.View) f.getParent()).getHeight() - top - nav;
                    if (h <= 0 || avail <= h) return;
                    XposedHelpers.setIntField(lp, "y", top + (avail - h) / 2);
                }
            });
        }
        // The dock's app drawer button: icon beside the words in landscape; icon only, as in
        // portrait, like the rest of the dock.
        Class<?> hotseat = XposedHelpers.findClassIfExists("com.android.launcher3.Hotseat", cl);
        if (hotseat != null) {
            XposedBridge.hookAllMethods(hotseat, "addAllAppsButton", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object launcher = XposedHelpers.getObjectField(param.thisObject, "mLauncher");
                    Object b = XposedHelpers.callMethod(launcher, "getAllAppsButton");
                    if (!(b instanceof android.widget.TextView)) return;
                    android.widget.TextView t = (android.widget.TextView) b;
                    android.graphics.drawable.Drawable[] ds = t.getCompoundDrawables();
                    android.graphics.drawable.Drawable icon = ds[0] != null ? ds[0] : ds[2];
                    if (icon == null) return;
                    t.setCompoundDrawables(null, icon, null, null);
                    t.setText(null);
                    t.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
                }
            });
        }

        Class<?> bubble = XposedHelpers.findClass("com.android.launcher3.BubbleTextView", cl);
        XposedBridge.hookAllConstructors(bubble, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                android.widget.TextView v = (android.widget.TextView) param.thisObject;
                if (!XposedHelpers.getBooleanField(v, "mLayoutHorizontal")) return;
                if (param.args.length < 2 || !(param.args[1] instanceof android.util.AttributeSet)) return;
                if (!isHomeIconStyle(v.getContext(), ((android.util.AttributeSet) param.args[1]).getStyleAttribute())) return;
                XposedHelpers.setBooleanField(v, "mLayoutHorizontal", false);
                v.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
                v.setPaddingRelative(v.getPaddingEnd(), v.getPaddingTop(), v.getPaddingEnd(), v.getPaddingBottom());
            }
        });

        // Edit mode's button bar (widgets, wallpaper, home settings...): the swivel home pads it
        // by 567px on each side in landscape, made for the 2460px top screen. On the 1240px
        // panel that left the buttons a sliver and every label wrapped letter by letter.
        Class<?> launcherClass = XposedHelpers.findClassIfExists("com.android.launcher3.Launcher", cl);
        XC_MethodHook barPadding = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object launcher = param.thisObject;
                if (!(launcher instanceof android.app.Activity)) {
                    launcher = XposedHelpers.getObjectField(param.thisObject, "mActivity");
                }
                // LauncherRootView also roots quickstep's recents screen, which has no edit bar.
                if (launcherClass == null || !launcherClass.isInstance(launcher)) return;
                Object bar = XposedHelpers.callMethod(launcher, "getLGOverviewPanel");
                if (!(bar instanceof android.view.View)) return;
                android.view.View v = (android.view.View) bar;
                if (v.getPaddingLeft() != 0 || v.getPaddingRight() != 0) {
                    v.setPadding(0, v.getPaddingTop(), 0, v.getPaddingBottom());
                }
            }
        };
        if (launcherClass != null) XposedBridge.hookAllMethods(launcherClass, "showOverviewMode", barPadding);
        Class<?> rootView = XposedHelpers.findClassIfExists("com.android.launcher3.LauncherRootView", cl);
        if (rootView != null) XposedBridge.hookAllMethods(rootView, "updateOverViewPanelPadding", barPadding);

        // Edit mode's page dots: in landscape the swivel home lifts them by a fixed amount made
        // for the top screen, which left them over the button bar's icons. They sit right
        // above the bar instead, in the gap between the page previews and the bar.
        Class<?> stateAnim = XposedHelpers.findClassIfExists("com.android.launcher3.WorkspaceStateTransitionAnimation", cl);
        if (stateAnim != null) {
            XposedBridge.hookAllMethods(stateAnim, "getPageIndicatorTranslationY", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length != 1) return;
                    Object launcher = XposedHelpers.getObjectField(param.thisObject, "mLauncher");
                    Object profile = XposedHelpers.callMethod(launcher, "getDeviceProfile");
                    if (!XposedHelpers.getBooleanField(profile, "isLandscape")
                            || !XposedHelpers.getBooleanField(profile, "allowRotation")) return;
                    if (!XposedHelpers.getBooleanField(param.args[0], "stateIsOverview")) return;
                    Object bar = XposedHelpers.callMethod(launcher, "getLGOverviewPanel");
                    Object dots = XposedHelpers.callMethod(launcher, "getPageindicator");
                    if (!(bar instanceof android.view.View) || !(dots instanceof android.view.View)) return;
                    android.view.View b = (android.view.View) bar, d = (android.view.View) dots;
                    if (b.getHeight() > 0 && d.getHeight() > 0) {
                        param.setResult(dotsAboveBar(b, d));
                        return;
                    }
                    // The bar is shown for the first time and not laid out yet: once it is,
                    // the dots move from where LG's animation put them.
                    b.addOnLayoutChangeListener(new android.view.View.OnLayoutChangeListener() {
                        @Override
                        public void onLayoutChange(android.view.View v, int l, int t, int r, int bottom,
                                                   int ol, int ot, int or, int ob) {
                            if (v.getHeight() == 0 || d.getHeight() == 0) return;
                            v.removeOnLayoutChangeListener(this);
                            d.animate().translationY(dotsAboveBar(v, d)).setDuration(150).start();
                        }
                    });
                }
            });
        }

        Class<?> folder = XposedHelpers.findClassIfExists("com.android.launcher3.folder.FolderIcon", cl);
        if (folder == null) return;
        XposedBridge.hookAllConstructors(folder, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                XposedHelpers.setBooleanField(param.thisObject, "mLayoutHorizontal", false);
            }
        });
        // The landscape folder layout places preview and name side by side; stack them.
        XposedBridge.hookAllMethods(folder, "fromXml", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object icon = param.getResult();
                if (!(icon instanceof android.view.ViewGroup)) return;
                android.view.ViewGroup g = (android.view.ViewGroup) icon;
                g.setPaddingRelative(0, g.getPaddingTop(), 0, g.getPaddingBottom());
                android.view.ViewGroup.LayoutParams own = g.getLayoutParams();
                if (own instanceof android.view.ViewGroup.MarginLayoutParams) {
                    ((android.view.ViewGroup.MarginLayoutParams) own).setMarginStart(0);
                }
                Object preview = XposedHelpers.getObjectField(icon, "mPreviewBackground");
                Object name = XposedHelpers.getObjectField(icon, "mFolderName");
                stack((android.view.View) preview, false);
                stack((android.view.View) name, true);
                // The name keeps the side-by-side layout's start alignment and padding: it sat
                // left of the folder icon. Centered under it like an app's label.
                if (name instanceof android.widget.TextView) {
                    android.widget.TextView t = (android.widget.TextView) name;
                    XposedHelpers.setBooleanField(t, "mLayoutHorizontal", false);
                    t.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
                    t.setPaddingRelative(0, t.getPaddingTop(), 0, t.getPaddingBottom());
                }
            }
        });
    }

    // The folder's content area (its swivel-landscape height less the footer) shared by its rows.
    private static int dimen(android.content.res.Resources r, String name) {
        int id = r.getIdentifier(name, "dimen", "android");
        return id == 0 ? 0 : r.getDimensionPixelSize(id);
    }

    private static int folderRowHeight(Object profile, String rowsField) {
        android.app.Application app = android.app.AndroidAppHelper.currentApplication();
        if (app == null) return 0;
        android.content.res.Resources r = app.getResources();
        int wrapper = r.getIdentifier("folder_content_wrapper_height_swivel_land", "dimen", PKG);
        int footer = r.getIdentifier("folder_footer_height", "dimen", PKG);
        int rows = XposedHelpers.getIntField(XposedHelpers.getObjectField(profile, "inv"), rowsField);
        if (wrapper == 0 || rows <= 0) return 0;
        int space = r.getDimensionPixelSize(wrapper) - (footer == 0 ? 0 : r.getDimensionPixelSize(footer));
        return space / rows;
    }

    private static void stack(android.view.View v, boolean fill) {
        if (v == null || !(v.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams)) return;
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) v.getLayoutParams();
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        lp.leftMargin = 0;
        lp.rightMargin = 0;
        if (fill) lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
        v.setLayoutParams(lp);
    }

    private static int iconStyle, folderIconStyle;

    // The home's icons use @style/Icon (folders: Icon.Folder); popup rows use other styles.
    private static boolean isHomeIconStyle(Context c, int style) {
        if (style == 0) return false;
        if (iconStyle == 0) {
            android.content.res.Resources r = c.getResources();
            iconStyle = r.getIdentifier("Icon", "style", PKG);
            folderIconStyle = r.getIdentifier("Icon.Folder", "style", PKG);
        }
        return style == iconStyle || style == folderIconStyle;
    }

    // --- Fresh layout after the main display changed ---

    // The main display the home was laid out for: {width, height, density}.
    private static int[] start;
    private static long startedAt;

    /**
     * LG's home keeps the grid it computed for the display it started on, and redoes it badly
     * when that display changes under it:
     *  - main screen mode changes the main display (another panel, size and density): names
     *    cut off, the edit bar squeezed letter by letter;
     *  - forced rotation turns the panel between portrait and landscape: coming back to
     *    landscape the whole home was drawn shrunk.
     * A freshly started home lays out right in either case. A panel switch restarts the whole
     * process at once (DisplaySwap.restartHome), so it starts on the new panel; what is left
     * here is rotation: when the home is shown (or turned while shown) on a main display unlike
     * the one it started on, it restarts once and lays itself out for the display it is on.
     */
    private static void installFreshLayout(ClassLoader cl) {
        Class<?> launcher = XposedHelpers.findClassIfExists("com.android.launcher3.Launcher", cl);
        if (launcher == null) return;
        XposedBridge.hookAllMethods(launcher, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (startedAt != 0) return;
                int[] m = mainDisplay((Context) param.thisObject);
                if (m == null) return;
                start = m;
                startedAt = android.os.SystemClock.elapsedRealtime();
            }
        });
        XC_MethodHook check = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (startedAt == 0) return;
                int[] m = mainDisplay((Context) param.thisObject);
                if (m == null || java.util.Arrays.equals(m, start)) return;
                if (android.os.SystemClock.elapsedRealtime() - startedAt < 3000) {
                    start = m; // just started and the display moved meanwhile: take it as the start
                    return;
                }
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        };
        XposedBridge.hookAllMethods(launcher, "onResume", check);
        // The home handles rotation itself (no restart of the activity): checked there too.
        XposedBridge.hookAllMethods(launcher, "onConfigurationChanged", check);
    }

    /**
     * LG's home keeps one "last density" and restarts itself when it starts at another one,
     * taking it for a screen zoom change. The two panels have their own densities, so every
     * main screen mode switch was a "zoom change": a second restart right after ours. A start
     * on another panel than last time is not a zoom change (the density is stored all the
     * same); a real zoom change on the same panel still restarts it as LG intended.
     */
    private static void installNoZoomRestart(ClassLoader cl) {
        Class<?> watcher = XposedHelpers.findClassIfExists("com.lge.launcher3.ScreenZoomChangeWatcher", cl);
        if (watcher == null) return;
        boolean[] panelMoved = {false};
        XposedBridge.hookAllMethods(watcher, "checkScreenZoomChangedOnCreate", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                panelMoved[0] = false;
                if (param.args.length == 0 || !(param.args[0] instanceof Context)) return;
                Context c = (Context) param.args[0];
                int[] now = panel(c);
                if (now == null) return;
                String key = now[0] + "x" + now[1];
                android.content.SharedPreferences p = c.getSharedPreferences("wingcustom_panel", Context.MODE_PRIVATE);
                String last = p.getString("panel", null);
                if (key.equals(last)) return;
                p.edit().putString("panel", key).apply();
                panelMoved[0] = last != null;
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (panelMoved[0]) param.setResult(false);
                panelMoved[0] = false;
            }
        });
        XposedBridge.hookAllMethods(watcher, "notifyScreenZoomListeners", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (panelMoved[0]) param.setResult(null);
            }
        });
    }

    // A folder page's rows, as many as fit sideways in main screen mode (see installFullIcons).
    private static void fitFolderRows(android.view.View paged) {
        Context c = paged.getContext();
        if (!mainScreenMode(c)) return;
        Object p = XposedHelpers.callMethod(c, "getDeviceProfile");
        if (XposedHelpers.getBooleanField(p, "isLandscape")
                || XposedHelpers.getIntField(p, "widthPx") <= XposedHelpers.getIntField(p, "heightPx")) return;
        android.graphics.Rect pad = (android.graphics.Rect) XposedHelpers.callMethod(p, "getWorkspacePadding", false);
        int cell = XposedHelpers.getIntField(p, "folderCellHeightPx");
        if (pad == null || cell <= 0) return;
        android.content.res.Resources r = c.getResources();
        int space = XposedHelpers.getIntField(p, "availableHeightPx") - pad.top - pad.bottom
                - dimen(r, "folder_title_height") - dimen(r, "folder_footer_height");
        int fit = Math.max(1, space / cell);
        // The page grid (Android 12+): its row count and items per page.
        Object grid = XposedHelpers.getObjectField(paged, "mOrganizer");
        int rows = XposedHelpers.getIntField(grid, "mMaxCountY");
        if (fit >= rows) return;
        XposedHelpers.setIntField(grid, "mMaxCountY", fit);
        XposedHelpers.setIntField(grid, "mMaxItemsPerPage", XposedHelpers.getIntField(grid, "mMaxCountX") * fit);
    }

    private static int dimen(android.content.res.Resources r, String name) {
        int id = r.getIdentifier(name, "dimen", PKG);
        return id == 0 ? 0 : r.getDimensionPixelSize(id);
    }

    // {width, height, density} of the main display as it is turned now.
    private static int[] mainDisplay(Context c) {
        try {
            android.hardware.display.DisplayManager dm = c.getSystemService(android.hardware.display.DisplayManager.class);
            android.view.Display d = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d == null) return null;
            android.util.DisplayMetrics out = new android.util.DisplayMetrics();
            d.getRealMetrics(out);
            return new int[] {out.widthPixels, out.heightPixels, out.densityDpi};
        } catch (Throwable t) {
            ModuleLog.w(t);
            return null;
        }
    }

    // --- Full-size icons on the second screen's panel ---

    /**
     * LG's home stacks the rows (icon + two label lines + padding) and, when they are taller
     * than the workspace, shrinks every icon (home and dock) by that ratio. The second screen's
     * panel as the main display is short (1080x1240), so the icons were always shrunk, and a
     * larger display size shrank them further instead of enlarging them. In main screen mode,
     * either orientation, the label takes one line (long names end in an ellipsis) and the
     * icons keep LG's own size, shrunk only where a row cannot hold one with its label and
     * the space between rows. Dock icons stay within the dock.
     */
    private static void installFullIcons(ClassLoader cl) {
        Class<?> lgProfile = XposedHelpers.findClassIfExists("com.lge.launcher3.profile.LGDeviceProfile", cl);
        if (lgProfile == null) return;
        // LG lays landscape out for the swivel's sideways top screen (its own dock, folder and
        // cell sizes). Main screen mode's sideways panel is close to square (1240x1080): its
        // landscape profile is worked out as portrait is, at the landscape size.
        XposedBridge.hookAllConstructors(lgProfile, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object[] a = param.args;
                if (a.length < 8 || !(a[0] instanceof Context) || !Boolean.TRUE.equals(a[6])
                        || !Boolean.FALSE.equals(a[7]) || !mainScreenMode((Context) a[0])) return;
                a[6] = false;
                // The usable size is taken from (min, max) by orientation: swapped, the
                // portrait reading gives the landscape size.
                Object min = a[2];
                a[2] = a[3];
                a[3] = min;
            }
        });
        // The open folder would still be inflated from LG's landscape layout (a side button
        // column, sized for the swivel's top screen), which only its landscape code measures:
        // it is inflated from the portrait one, the layout the profile is worked out for.
        Class<?> folder = XposedHelpers.findClassIfExists("com.android.launcher3.folder.Folder", cl);
        if (folder != null) {
            XposedBridge.hookAllMethods(folder, "fromXml", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1 || !(param.args[0] instanceof android.app.Activity)) return;
                    android.app.Activity a = (android.app.Activity) param.args[0];
                    android.content.res.Configuration conf = a.getResources().getConfiguration();
                    if (conf.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            || !mainScreenMode(a)) return;
                    int id = a.getResources().getIdentifier(folderLayout(cl), "layout", PKG);
                    if (id == 0) return;
                    android.content.res.Configuration port = new android.content.res.Configuration(conf);
                    port.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT;
                    port.screenWidthDp = conf.screenHeightDp;
                    port.screenHeightDp = conf.screenWidthDp;
                    try (android.content.res.XmlResourceParser parser =
                                 a.createConfigurationContext(port).getResources().getLayout(id)) {
                        param.setResult(a.getLayoutInflater().inflate(parser, null));
                    }
                }
            });
        }
        // Sideways, a folder of home-sized rows is taller than the panel (a folder page does not
        // scroll): its last rows and the footer went off the screen. Shrinking the rows cut the
        // names under the icons instead. The rows keep their size and a page holds as many as
        // fit under the title and above the footer; the rest go to the next page.
        Class<?> folderPaged = XposedHelpers.findClassIfExists("com.android.launcher3.folder.FolderPagedView", cl);
        if (folderPaged != null) XposedBridge.hookAllMethods(folderPaged, "setFolder", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    fitFolderRows((android.view.View) param.thisObject);
                } catch (Throwable t) {
                    ModuleLog.w(t); // LG's folder differs: its own layout stays
                }
            }
        });
        XposedBridge.hookAllMethods(lgProfile, "updateAvailableDimensions", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object p = param.thisObject;
                if (param.args.length < 3 || XposedHelpers.getBooleanField(p, "isTablet")) return;
                Context c = (Context) param.args[0];
                if (!mainScreenMode(c)) return;
                int padding = XposedHelpers.getIntField(p, "iconDrawablePaddingOriginalPx");
                Object res = param.args[2], metrics = param.args[1];
                XposedHelpers.callMethod(p, "updateIconSize", c, 1f, padding, res, metrics);
                // LG's own icon size at scale 1.
                int icon = XposedHelpers.getIntField(p, "iconSizePx");
                if (XposedHelpers.getIntField(p, "widthPx") > XposedHelpers.getIntField(p, "heightPx")) {
                    roomierSideways(p, icon);
                }
                // The cell the workspace actually gives each item: LG's getCellSize leaves out
                // the nav bar, which the workspace keeps clear below its rows as well.
                android.graphics.Point size = (android.graphics.Point) XposedHelpers.callMethod(p, "getCellSize");
                int nav = XposedHelpers.getIntField(p, "heightPx") - XposedHelpers.getIntField(p, "availableHeightPx")
                        - dimen(c.getResources(), "status_bar_height");
                int rows = XposedHelpers.getIntField(p, "numRows");
                if (nav > 0 && rows > 0) size.y -= Math.round((float) nav / rows);
                if (icon <= 0 || size.x <= 0 || size.y <= 0) {
                    param.setResult(null);
                    return;
                }
                // The label is drawn at LG's text size times the font scale (LG's own cell math
                // leaves the scale out): one line's full height, descenders (g, y) included.
                int text = XposedHelpers.getIntField(p, "iconTextSizePx");
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setTextSize(text * c.getResources().getConfiguration().fontScale);
                android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
                float line = fm.bottom - fm.top;
                // LG's own icon size whatever the grid (as in other home apps), smaller only
                // where a row has no room for it: LG's icon-to-label gap above the one-line
                // label and the same gap below it, between the rows.
                float fit = Math.min((size.y - 2 * padding - line) / icon, 0.8f * size.x / icon);
                float scale = Math.max(0f, Math.min(1f, fit));
                // A label that has no room is left out, never cut off.
                boolean label = icon * scale + padding + line <= size.y;
                XposedHelpers.setIntField(p, "iconTextMaxLines", 1);
                XposedHelpers.callMethod(p, "updateIconSize", c, scale, padding, res, metrics);
                XposedHelpers.setIntField(p, "iconTextSizePx", label ? text : 0);
                // The dock has its own height: its icons keep LG's own size within it (at most
                // 80% of the bar, LG's rule) instead of shrinking with the workspace's rows.
                int bar = XposedHelpers.getIntField(p, "hotseatBarHeightPx");
                if (bar > 0) XposedHelpers.setIntField(p, "hotseatIconSizePx", Math.min(icon, Math.round(bar * 0.8f)));
                param.setResult(null);
            }
        });
        installGridPresets(cl);
    }

    private static final String LG_TOP_MARGIN = "wingcustom_top_margin";

    /**
     * Sideways, the profile worked out as portrait keeps portrait's top margin and dock, made
     * for a panel 160 px taller: the rows were left little room. The top margin takes a
     * quarter of LG's (LG's value kept, never compounding), and the dock no more than a
     * full-size icon needs (LG's dock icons take at most 80% of it).
     */
    private static void roomierSideways(Object p, int icon) {
        Object kept = XposedHelpers.getAdditionalInstanceField(p, LG_TOP_MARGIN);
        int top = kept instanceof Integer ? (Integer) kept : 0;
        if (top <= 0) {
            top = XposedHelpers.getIntField(p, "desiredWorkspaceTopMarginPx");
            if (top > 0) XposedHelpers.setAdditionalInstanceField(p, LG_TOP_MARGIN, top);
        }
        if (top > 0) XposedHelpers.setIntField(p, "desiredWorkspaceTopMarginPx", top / 4);
        int bar = XposedHelpers.getIntField(p, "hotseatBarHeightPx");
        int need = Math.round(icon / 0.8f);
        if (bar > need && need > 0) XposedHelpers.setIntField(p, "hotseatBarHeightPx", need);
    }

    // The open folder's layout, as Folder.fromXml picks it.
    private static String folderLayout(ClassLoader cl) {
        Object ux10 = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("com.lge.launcher3.util.Utilities", cl), "isLGUI10_0");
        return Boolean.TRUE.equals(ux10) ? "user_folder_ux10_0" : "user_folder";
    }

    private static final String GRID_PRESETS = "config_dynamic_grid_preset";
    // The home's grids. The edit mode's grid panel has exactly five slots: 4x3 takes the place
    // of 5x6, the densest.
    private static final String[] GRIDS = {"4x3", "4x4", "4x5", "4x6", "5x5"};
    private static int gridPresetsId;

    /**
     * LG's grid choices (settings dialog, edit mode panel, and the check that a saved grid is
     * still valid) all come from one string array. It lists 4x3 for big icons, on either
     * screen: the same list in both modes also keeps LG's check from putting a saved 4x3 back
     * to the default grid after a trip to the other mode.
     */
    private static void installGridPresets(ClassLoader cl) {
        // LG reads the landscape dock's height per grid (dev_prof_hotseat_height_4x4_..._land);
        // 4x3 has none, which gave a zero-height dock: it takes 4x4's.
        Class<?> lgProfile = XposedHelpers.findClassIfExists("com.lge.launcher3.profile.LGDeviceProfile", cl);
        if (lgProfile != null) {
            XposedBridge.hookAllMethods(lgProfile, "getLandscapeHotseatBarSizeByDynamicGrid", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object[] a = param.args;
                    if (!(param.getResult() instanceof Integer) || (Integer) param.getResult() > 0
                            || a.length < 4 || !(a[2] instanceof Integer) || (Integer) a[2] == 4) return;
                    Object[] four = a.clone();
                    four[2] = 4; // rows
                    four[3] = 4; // columns
                    param.setResult(XposedBridge.invokeOriginalMethod(param.method, param.thisObject, four));
                }
            });
        }
        XposedBridge.hookAllMethods(android.content.res.Resources.class, "getStringArray", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length != 1 || !(param.args[0] instanceof Integer)) return;
                android.content.res.Resources r = (android.content.res.Resources) param.thisObject;
                if (gridPresetsId == 0) gridPresetsId = r.getIdentifier(GRID_PRESETS, "array", PKG);
                if ((Integer) param.args[0] != gridPresetsId || gridPresetsId == 0) return;
                param.setResult(GRIDS.clone());
            }
        });
    }

    private static boolean mainScreenMode(Context c) {
        return android.provider.Settings.Global.getInt(c.getContentResolver(), SwivelFold.KEY_SWAPPED, 0) == 1;
    }

    // --- Home grid 4x4, once ---

    /** Settings.Global, 1: switch LG's home grid to 4x4 once (set when main screen mode is kept). */
    static final String KEY_GRID_ONCE = "godaijishou_grid_once";
    private static final String GRID_MANAGER = "com.lge.launcher3.dynamicgrid.DynamicGridManager";
    private static final String DONE_PREFS = "wingcustom_hook", DONE_KEY = "grid_4x4_done";
    private static volatile boolean gridDone;

    /**
     * LG's default 5x5 grid is cramped on the second screen's panel as the main display. The
     * first time main screen mode is kept, the next time the home shows it switches to 4x4
     * through LG's own grid change (what its settings' grid picker runs, items moved along).
     * Only ever once: a grid picked afterwards is the user's.
     */
    private static void installGridOnce(ClassLoader cl) {
        Class<?> launcher = XposedHelpers.findClassIfExists("com.android.launcher3.Launcher", cl);
        Class<?> manager = XposedHelpers.findClassIfExists(GRID_MANAGER, cl);
        if (launcher == null || manager == null) return;
        XposedBridge.hookAllMethods(launcher, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (gridDone) return;
                try {
                    android.app.Activity a = (android.app.Activity) param.thisObject;
                    if (android.provider.Settings.Global.getInt(a.getContentResolver(), KEY_GRID_ONCE, 0) != 1) return;
                    android.content.SharedPreferences p = a.getSharedPreferences(DONE_PREFS, Context.MODE_PRIVATE);
                    gridDone = true;
                    if (p.getBoolean(DONE_KEY, false)) return;
                    p.edit().putBoolean(DONE_KEY, true).apply(); // first: never twice, even on a failure
                    a.getWindow().getDecorView().post(() -> toFourByFour(manager, a));
                } catch (Throwable t) {
                    ModuleLog.w(t);
                }
            }
        });
    }

    private static void toFourByFour(Class<?> managerClass, android.app.Activity a) {
        try {
            Object m = XposedHelpers.newInstance(managerClass, a);
            XposedHelpers.callMethod(m, "init");
            java.util.List<?> grids = (java.util.List<?>) XposedHelpers.getObjectField(m, "mGrids");
            int current = XposedHelpers.getIntField(m, "mSelectedGridIndex");
            if (grids == null) return;
            for (int i = 0; i < grids.size(); i++) {
                Object g = grids.get(i);
                if (!(g instanceof int[])) continue;
                int[] cr = (int[]) g; // {columns, rows}
                if (cr.length >= 2 && cr[0] == 4 && cr[1] == 4) {
                    if (i != current) XposedHelpers.callMethod(m, "runDynamicGrid", i);
                    return;
                }
            }
        } catch (Throwable t) {
            ModuleLog.w(t);
        }
    }

    private static void folded(Class<?> callback) {
        // Quickstep's gesture service hears the real state: on "open" it puts its home and
        // recents gesture watcher on the second screen, on anything else it drops it. Folded,
        // the second screen lost its gestures at the first swivel event (every mode switch).
        if (callback.getName().startsWith("com.android.quickstep.")) return;
        for (Class<?> c = callback; c != null && c != Object.class; c = c.getSuperclass()) {
            if (!hookedCallbacks.add(c)) return; // this class (and its parents) done already
            try {
                XposedBridge.hookAllMethods(c, "onSwivelStateChanged", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                        int s = (Integer) param.args[0];
                        if (s == OPENING) param.args[0] = CLOSING;
                        else if (s == OPENED) param.args[0] = CLOSED;
                    }
                });
            } catch (Throwable t) {
                ModuleLog.w(t);
            }
        }
    }
}
