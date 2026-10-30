package com.xlollx.songport.server

import org.eclipse.swt.SWT
import org.eclipse.swt.browser.Browser
import org.eclipse.swt.browser.LocationAdapter
import org.eclipse.swt.browser.LocationEvent
import org.eclipse.swt.graphics.Image
import org.eclipse.swt.layout.FillLayout
import org.eclipse.swt.widgets.Display
import org.eclipse.swt.widgets.Menu
import org.eclipse.swt.widgets.MenuItem
import org.eclipse.swt.widgets.Shell
import org.eclipse.swt.widgets.TrayItem
import kotlin.system.exitProcess

/**
 * Trackhop Desktop's own window: the pages in a window of their own (SWT's Browser on the system's
 * web engine: Edge's WebView2 on Windows, WebKitGTK on Linux), with Trackhop's icon in the taskbar
 * and in the tray. Closing the window leaves Trackhop running in the tray, where it keeps the
 * scheduled syncs; where there is no tray, closing it quits. Links to other sites open in the
 * system's browser. Runs on the main thread until Trackhop quits.
 *
 * Only Desktop loads this class, and only when the SWT jar is there (package.sh adds it): Docker and
 * a plain build fall back to the system's browser.
 */
class DesktopWindow(private val url: String) {
    private val display = Display.getDefault().also { Display.setAppName("Trackhop") }
    private val icon = DesktopWindow::class.java.getResourceAsStream("/desktop/tray.png")?.use { Image(display, it) }
    private var shell: Shell? = null
    private var tray: TrayItem? = null

    /** Shows the window (made on first use); false when the system has no usable web engine. */
    fun show(): Boolean {
        shell?.takeIf { !it.isDisposed }?.let { it.isVisible = true; it.minimized = false; it.forceActive(); return true }
        val s = Shell(display)
        s.text = "Trackhop"
        icon?.let { s.image = it }
        s.layout = FillLayout()
        val browser = try {
            Browser(s, if (windows) SWT.EDGE else SWT.NONE)
        } catch (e: Throwable) {
            Log.add("desktop", "no web engine for a window (${e.message}): using the browser")
            s.dispose()
            return false
        }
        browser.addLocationListener(object : LocationAdapter() {
            override fun changing(event: LocationEvent) {
                // A sign-in leaves for the service and comes back to /callback: that stays here. A
                // link the page marks as external (see Html: data-external) goes to the browser.
                if (event.location.startsWith("trackhop-external:")) {
                    event.doit = false
                    DesktopApp.openBrowser(event.location.removePrefix("trackhop-external:"))
                }
            }
        })
        browser.addOpenWindowListener { event ->
            // target=_blank: the system's browser gets it, through a browser that is never shown.
            val probe = Browser(Shell(display), if (windows) SWT.EDGE else SWT.NONE)
            probe.addLocationListener(object : LocationAdapter() {
                override fun changing(e: LocationEvent) {
                    e.doit = false
                    DesktopApp.openBrowser(e.location)
                    display.asyncExec { probe.shell.dispose() }
                }
            })
            event.browser = probe
        }
        s.addListener(SWT.Close) { event ->
            if (tray != null) { event.doit = false; s.isVisible = false } else quit()
        }
        browser.url = url
        s.setSize(1180, 820)
        s.open()
        shell = s
        return true
    }

    /** The tray icon and its menu; false where the desktop has none. */
    fun installTray(): Boolean {
        val bar = display.systemTray ?: return false
        val item = TrayItem(bar, SWT.NONE)
        item.toolTipText = "Trackhop"
        icon?.let { item.image = it }
        val menu = Menu(Shell(display), SWT.POP_UP)
        fun entry(text: String, style: Int = SWT.PUSH, action: (MenuItem) -> Unit) =
            MenuItem(menu, style).apply { this.text = text; addListener(SWT.Selection) { action(this) } }
        entry("Open Trackhop") { if (!show()) DesktopApp.openBrowser(url) }
        entry("Open in the browser") { DesktopApp.openBrowser(url) }
        if (Autostart.available) entry("Start with the computer", SWT.CHECK) { Autostart.set(it.selection) }.selection = Autostart.enabled()
        MenuItem(menu, SWT.SEPARATOR)
        entry("Quit Trackhop") { quit() }
        item.addListener(SWT.MenuDetect) { menu.isVisible = true }
        item.addListener(SWT.DefaultSelection) { if (!show()) DesktopApp.openBrowser(url) }
        item.addListener(SWT.Selection) { if (!windows) if (!show()) DesktopApp.openBrowser(url) }
        tray = item
        return true
    }

    /** [show] from any thread (a second start asking the running one). */
    fun showLater() = display.asyncExec { if (!show()) DesktopApp.openBrowser(url) }

    /** The event loop, until Trackhop quits. */
    fun run() {
        while (!display.isDisposed) if (!display.readAndDispatch()) display.sleep()
    }

    /** Called from any thread (the page's Quit button). */
    fun quit() {
        runCatching { display.asyncExec { tray?.dispose(); display.dispose() } }
        exitProcess(0)
    }


    private companion object {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }
}
