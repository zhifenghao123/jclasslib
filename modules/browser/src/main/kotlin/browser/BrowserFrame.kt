/*
    This library is free software; you can redistribute it and/or
    modify it under the terms of the GNU General Public
    License as published by the Free Software Foundation; either
    version 2 of the license, or (at your option) any later version.
*/

package org.gjt.jclasslib.browser

import com.install4j.api.Util
import com.install4j.runtime.alert.AlertType
import com.install4j.runtime.filechooser.DirectoryChooser
import com.install4j.runtime.filechooser.FileAccessMode
import com.install4j.runtime.filechooser.FileChooser
import com.install4j.runtime.filechooser.MultiFileFilter
import kotlinx.dom.build.addElement
import kotlinx.dom.createDocument
import kotlinx.dom.parseXml
import kotlinx.dom.writeXmlString
import org.gjt.jclasslib.browser.config.BrowserConfig
import org.gjt.jclasslib.browser.config.classpath.ClasspathArchiveEntry
import org.gjt.jclasslib.browser.config.classpath.ClasspathBrowser
import org.gjt.jclasslib.browser.config.classpath.ClasspathEntry
import org.gjt.jclasslib.browser.config.classpath.ClasspathSetupDialog
import org.gjt.jclasslib.structures.InvalidByteCodeException
import org.gjt.jclasslib.util.DefaultAction
import org.gjt.jclasslib.util.GUIHelper
import org.gjt.jclasslib.util.GUIHelper.applyPath
import org.gjt.jclasslib.util.darkMode
import org.w3c.dom.Document
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileNotFoundException
import java.io.FileWriter
import java.io.IOException
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.xml.transform.OutputKeys
import kotlin.math.max
import kotlin.math.min

class BrowserFrame : JFrame() {

    val config: BrowserConfig = BrowserConfig()

    val openClassFileAction = DefaultAction("Open class file", "Open a class file", "open_small.png", "open_large.png") {
        if (classesFileChooser.select()) {
            repaintNow()
            withWaitCursor {
                val file = classesFileChooser.selectedFile
                classesChooserPath = file.parent
                val lowerCasePath = file.path.toLowerCase()
                when {
                    lowerCasePath.endsWith(".class") -> openClassFromFile(file)
                    lowerCasePath.endsWith(".jar") -> openClassFromJar(file)
                    else -> GUIHelper.showMessage(this, "Please select a class file or a JAR file", AlertType.WARNING)
                }
            }
        }
    }

    val browseClasspathAction = DefaultAction("Browse classpath", "Browse the current classpath to open a class file", "tree_small.png", "tree_large.png") {
        classpathBrowser.isVisible = true
        if (!classpathBrowser.isCanceled) {
            classpathBrowser.selectedClassNames.forEach { selectedClassName ->
                val findResult = config.findClass(selectedClassName, classpathBrowser.isModulePathSelection())
                if (findResult != null) {
                    repaintNow()
                    withWaitCursor {
                        frameContent.openClassFile(findResult.fileName, findResult.moduleName)
                    }
                } else {
                    GUIHelper.showMessage(this, "Error loading $selectedClassName", AlertType.ERROR)
                }
            }
        }
    }

    val setupClasspathAction = DefaultAction("Setup classpath", "Configure the classpath") {
        classpathSetupDialog.isVisible = true
    }

    val newWindowAction = DefaultAction("New window", "Open a new window") {
        saveWindowSettings()
        BrowserFrame().isVisible = true
    }.apply {
        accelerator(KeyEvent.VK_N)
    }

    val newWorkspaceAction = DefaultAction("New workspace", "Close all class files and open a new workspace") {
        frameContent.closeAllTabs()
        workspaceFile = null
        config.clear()
        updateTitle()
    }

    val openWorkspaceAction = DefaultAction("Open workspace", "Open workspace from disk", "open_ws_small.png", "open_ws_large.png") {
        workspaceFileChooser.fileAccessMode(FileAccessMode.OPEN)
        if (workspaceFileChooser.select()) {
            val selectedFile = workspaceFileChooser.selectedFile
            openWorkspace(selectedFile)
            workspaceChooserPath = selectedFile.parent
        }
    }

    val saveWorkspaceAction = DefaultAction("Save workspace", "Save current workspace to disk", "save_ws_small.png", "save_ws_large.png") {
        val workspaceFile = this.workspaceFile
        if (workspaceFile != null) {
            saveWorkspaceToFile(workspaceFile)
        } else {
            saveWorkspace()
        }
    }

    val saveClassesAction = DefaultAction("Save all open classes", "Save all open classes to a selected directory") {
        if (saveClassesFileChooser.select()) {
            frameContent.saveClassesToDirectory(saveClassesFileChooser.selectedFile)
        }
    }

    val saveWorkspaceAsAction = DefaultAction("Save workspace as", "Save current workspace to a different file") {
        saveWorkspace()
    }.apply {
        disabled()
    }

    val quitAction = DefaultAction("Quit") {
        saveWindowSettings()
        exit()
    }

    val closeAction = DefaultAction("Close window") {
        saveWindowSettings()
        isVisible = false
        dispose()
        if (getBrowserFrames().isEmpty()) {
            exit()
        }
    }.apply {
        accelerator(KeyEvent.VK_W)
    }

    val backwardAction = DefaultAction("Backward", "Move backward in the navigation history", "browser_backward_small.png", "browser_backward_large.png") {
        frameContent.selectedTab?.browserComponent?.history?.historyBackward()
    }.apply {
        disabled()
        accelerator(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK)
    }

    val forwardAction = DefaultAction("Forward", "Move backward in the navigation history", "browser_forward_small.png", "browser_forward_large.png") {
        frameContent.selectedTab?.browserComponent?.history?.historyForward()
    }.apply {
        disabled()
        accelerator(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK)
    }

    val reloadAction = DefaultAction("Reload", "Reload class file", "reload_small.png", "reload_large.png") {
        try {
            frameContent.selectedTab?.reload()
        } catch (e: IOException) {
            GUIHelper.showMessage(this, e)
        }

    }.apply {
        disabled()
        accelerator(KeyEvent.VK_R)
    }

    val showHomepageAction = DefaultAction("jclasslib web site", "Visit jclasslib on the web", "web_small.png", "web_large.png") {
        GUIHelper.showURL(WEBSITE_URL)
    }

    val showEjtAction = DefaultAction("ej-technologies on the web", "Visit ej-technologies on the web", "web_small.png") {
        GUIHelper.showURL("http://www.ej-technologies.com")
    }

    val darkModeAction = DefaultAction("Dark mode", "Toggle dark mode for the UI") {
        darkMode = it.getValue(Action.SELECTED_KEY) as Boolean
        darkModeChanged()
    }.apply {
        putValue(Action.SELECTED_KEY, darkMode)
    }

    val aboutAction = DefaultAction("About the jclasslib bytecode viewer", "Show the jclasslib documentation") {
        BrowserAboutDialog(this).isVisible = true
    }

    val previousWindowAction = DefaultAction("Previous window", "Cycle to the previous open window") {
        getPreviousBrowserFrame(this).toFront()
    }.apply {
        accelerator(KeyEvent.VK_F2)
    }

    val nextWindowAction = DefaultAction("Next window", "Cycle to the next open window") {
        getNextBrowserFrame(this).toFront()
    }.apply {
        accelerator(KeyEvent.VK_F3)
    }

    val splitActions = SplitMode.values().associate { splitMode ->
        splitMode to DefaultAction(splitMode.actionName, splitMode.actionDescription) {
            frameContent.split(splitMode)
        }.apply {
            accelerator(splitMode.accelerator, DefaultAction.MENU_MODIFIER or KeyEvent.SHIFT_DOWN_MASK)
        }
    }

    val windowMenu = JMenu("Window").apply {
        add(previousWindowAction)
        add(nextWindowAction)
        addSeparator()
        splitActions.values.forEach {
            add(it)
        }
        popupMenu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) {
                checkWindowActions()
            }

            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) {
            }

            override fun popupMenuCanceled(event: PopupMenuEvent) {
            }
        })
    }

    private val normalFrameBounds: Rectangle
        get() = if (isMaximized) getDefaultFrameBounds() else bounds

    private val isMaximized: Boolean
        get() = extendedState and Frame.MAXIMIZED_BOTH != 0

    private val frameContent: FrameContent = FrameContent(this)

    private var workspaceFile: File? = null
    private var workspaceChooserPath = ""
    var classesChooserPath = ""
    var jreChooserPath = ""

    private val workspaceFileChooser: FileChooser by lazy {
        FileChooser.create()
            .parent(this)
            .title("Choose workspace file")
            .applyPath(workspaceChooserPath)
            .addFileFilter(MultiFileFilter(WORKSPACE_FILE_SUFFIX, "jclasslib workspace files"))
    }

    private val saveClassesFileChooser: DirectoryChooser by lazy {
        DirectoryChooser.create()
            .parent(this)
            .title("Save open classes to directory")
            .applyPath(workspaceChooserPath)
    }

    private val classesFileChooser: FileChooser by lazy {
        FileChooser.create()
            .title("Choose class file or jar file")
            .applyPath(classesChooserPath)
            .addFileFilter(MultiFileFilter(arrayOf("class", "jar"), "class files and jar files"))
            .addFileFilter(MultiFileFilter("class", "class files"))
            .addFileFilter(MultiFileFilter("jar", "jar files"))
    }

    private val recentMenu: RecentMenu = RecentMenu(this)
    private val classpathSetupDialog: ClasspathSetupDialog by lazy { ClasspathSetupDialog(this) }
    private val classpathBrowser: ClasspathBrowser by lazy { ClasspathBrowser(this, "Choose From Configured Classpath", true) }
    private val jarBrowser: ClasspathBrowser by lazy { ClasspathBrowser(this, "Choose From Classes In Selected JAR File", false) }

    fun openWorkspace(file: File) {

        frameContent.closeAllTabs()

        object : SwingWorker<Document, Unit>() {
            override fun doInBackground(): Document = parseXml(file)

            override fun done() {
                get().documentElement.apply {
                    config.readWorkspace(this)
                    frameContent.readWorkspace(this)
                }
                recentMenu.addRecentWorkspace(file)
                workspaceFile = file
                updateTitle()
                saveWorkspaceAsAction.isEnabled = true
            }
        }.execute()
    }

    fun openClassFromFile(file: File): BrowserTab {
        val tab = frameContent.openClassFile(file.path)
        val classFile = tab.classFile
        try {
            val pathComponents = classFile.thisClassName.split("/".toRegex())
            val currentDirectory = findClassPathRootDirectory(file, pathComponents)
            if (currentDirectory != null) {
                config.addClasspathDirectory(currentDirectory.path)
            }
        } catch (e: InvalidByteCodeException) {
        }
        return tab
    }

    private fun findClassPathRootDirectory(file: File, pathComponents: List<String>): File? {
        var currentDirectory = file.parentFile
        for (i in pathComponents.size - 2 downTo 0) {
            if (currentDirectory.name != pathComponents[i]) {
                return null
            }
            currentDirectory = currentDirectory.parentFile
        }
        return currentDirectory
    }

    fun openExternalFile(path: String) {
        EventQueue.invokeLater {
            val file = File(path)
            if (file.exists()) {
                if (path.toLowerCase().endsWith(".$WORKSPACE_FILE_SUFFIX")) {
                    openWorkspace(file)
                } else if (path.toLowerCase().endsWith(".class")) {
                    try {
                        openClassFromFile(file)
                    } catch (e: IOException) {
                        GUIHelper.showMessage(this@BrowserFrame, e)
                    }
                }
            }
        }
    }

    private fun setupMenu() {
        jMenuBar = JMenuBar().apply {

            add(JMenu("File").apply {
                add(openClassFileAction)
                addSeparator()
                add(newWindowAction)
                add(newWorkspaceAction)
                add(openWorkspaceAction)
                recentMenu.addTo(this)
                addSeparator()
                add(saveWorkspaceAction)
                add(saveWorkspaceAsAction)
                add(saveClassesAction)
                addSeparator()
                add(showHomepageAction)
                add(showEjtAction)
                addSeparator()
                add(JCheckBoxMenuItem(darkModeAction))
                addSeparator()
                add(closeAction)
                add(quitAction)
            })

            add(JMenu("Classpath").apply {
                add(browseClasspathAction)
                add(setupClasspathAction)
            })

            add(JMenu("Browse").apply {
                add(backwardAction)
                add(forwardAction)

                addSeparator()
                add(reloadAction)
            })

            add(windowMenu)

            add(JMenu("Help").apply {
                add(showHomepageAction)
                add(aboutAction)
            })
        }

    }

    private fun setupWindowBounds() {
        val activeBrowserFrame = getActiveBrowserFrame()
        if (activeBrowserFrame != null) {
            bounds = activeBrowserFrame.normalFrameBounds.apply {
                x += NEW_FRAME_OFFSET
                y += NEW_FRAME_OFFSET
            }
        } else {
            loadWindowSettings()
        }
    }

    private fun setupFrame() {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE

        val contentPane = contentPane as JComponent
        contentPane.layout = BorderLayout(5, 5)
        contentPane.add(frameContent, BorderLayout.CENTER)
        contentPane.add(buildToolbar(), BorderLayout.NORTH)
        iconImages = ICON_IMAGES

        contentPane.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                val supported = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                if (supported) {
                    support.dropAction = COPY
                }
                return supported
            }

            override fun importData(support: TransferSupport): Boolean {
                val transferable = support.transferable
                val flavors = transferable.transferDataFlavors
                for (flavor in flavors) {
                    try {
                        if (flavor.isFlavorJavaFileListType) {
                            @Suppress("UNCHECKED_CAST")
                            (transferable.getTransferData(flavor) as List<File>).forEach {
                                openExternalFile(it.path)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                }
                return false
            }
        }

    }

    private fun updateTitle() {
        val workspaceFile = this.workspaceFile
        if (workspaceFile == null) {
            title = APPLICATION_TITLE
            saveWorkspaceAsAction.isEnabled = false
        } else {
            title = APPLICATION_TITLE + " [" + workspaceFile.name + "]"
        }
    }

    private fun buildToolbar(): JToolBar = JToolBar().apply {
        add(openClassFileAction.createToolBarButton())
        add(browseClasspathAction.createToolBarButton())
        addSeparator()
        add(openWorkspaceAction.createToolBarButton())
        add(saveWorkspaceAction.createToolBarButton())
        addSeparator()
        add(backwardAction.createToolBarButton())
        add(forwardAction.createToolBarButton())
        addSeparator()
        add(reloadAction.createToolBarButton())

        isFloatable = false
    }

    private fun repaintNow() {
        val contentPane = contentPane as JComponent
        contentPane.paintImmediately(0, 0, contentPane.width, contentPane.height)
        val menuBar = jMenuBar
        menuBar.paintImmediately(0, 0, menuBar.width, menuBar.height)
    }

    private fun loadSettings() {
        getPreferencesNode().apply {
            workspaceChooserPath = get(SETTINGS_WORKSPACE_CHOOSER_PATH, workspaceChooserPath)
            classesChooserPath = get(SETTINGS_CLASSES_CHOOSER_PATH, classesChooserPath)
            jreChooserPath = get(SETTINGS_JRE_CHOOSER_PATH, jreChooserPath)
            recentMenu.read(this)
        }
    }

    private fun saveWorkspace() {
        workspaceFileChooser.fileAccessMode(FileAccessMode.SAVE)
        if (workspaceFileChooser.select()) {
            val selectedFile = getWorkspaceFile(workspaceFileChooser.selectedFile)
            if (!selectedFile.exists() || Util.isMacOS() || GUIHelper.showOptionDialog(this,
                    "File exists",
                    "The file " + selectedFile.path + "\nexists. Do you want to overwrite this file?",
                    GUIHelper.YES_NO_OPTIONS,
                    AlertType.QUESTION) == 0) {
                saveWorkspaceToFile(selectedFile)
                workspaceFile = selectedFile
                updateTitle()
                workspaceChooserPath = selectedFile.parent
            }
        }
    }

    private fun getWorkspaceFile(selectedFile: File): File =
            if (!selectedFile.name.toLowerCase().endsWith(".$WORKSPACE_FILE_SUFFIX")) {
                File(selectedFile.path + "." + WORKSPACE_FILE_SUFFIX)
            } else {
                selectedFile
            }

    private fun saveWorkspaceToFile(file: File) {
        try {
            FileWriter(file).use { writer ->
                createDocument().addElement("workspace") {
                    config.saveWorkspace(this)
                    frameContent.saveWorkspace(this)
                }.writeXmlString(writer, mapOf(
                        OutputKeys.INDENT to "yes",
                        OutputKeys.STANDALONE to "yes",
                        "{http://xml.apache.org/xslt}indent-amount" to "2"
                ))
            }
            recentMenu.addRecentWorkspace(file)
        } catch (e: IOException) {
            GUIHelper.showMessage(this, "An error occurred while saving to " + file.path, AlertType.ERROR)
        }

        GUIHelper.showMessage(this, "Workspace saved to " + file.path, AlertType.INFORMATION)
        saveWorkspaceAsAction.isEnabled = true
    }

    fun openClassFromJar(file: File) {
        val entry = ClasspathArchiveEntry(file.path)
        jarBrowser.apply {
            clear()
            classpathComponent = entry
            isVisible = true
        }
        if (!jarBrowser.isCanceled) {
            jarBrowser.selectedClassNames.forEach { selectedClassName ->
                val classPathClassName = ClasspathEntry.getClassPathClassName(selectedClassName, jarBrowser.isModulePathSelection())
                val fileName = file.path + "!" + classPathClassName + ".class"
                frameContent.openClassFile(fileName)
                config.addClasspathArchive(file.path)
            }
        }
    }

    private fun withWaitCursor(function: () -> Unit) {
        cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
        try {
            function.invoke()
        } catch (e: FileNotFoundException) {
            GUIHelper.showMessage(this, "File not found: " + e.message, AlertType.ERROR)
        } catch (e: IOException) {
            GUIHelper.showMessage(this, e)
        } finally {
            cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
        }
    }

    private fun setupEventHandlers() {
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) {
                closeAction()
            }

            override fun windowActivated(p0: WindowEvent?) {
                checkWindowActions()
            }
        })
    }

    private fun checkWindowActions() {
        val multipleWindows = getBrowserFrames().size > 1
        nextWindowAction.isEnabled = multipleWindows
        previousWindowAction.isEnabled = multipleWindows
    }

    private fun saveWindowSettings() {
        getPreferencesNode().apply {
            putBoolean(SETTINGS_WINDOW_MAXIMIZED, isMaximized)
            if (!isMaximized) {
                val frameBounds = bounds
                putInt(SETTINGS_WINDOW_WIDTH, frameBounds.width)
                putInt(SETTINGS_WINDOW_HEIGHT, frameBounds.height)
                putInt(SETTINGS_WINDOW_X, frameBounds.x)
                putInt(SETTINGS_WINDOW_Y, frameBounds.y)
            }

            put(SETTINGS_WORKSPACE_CHOOSER_PATH, workspaceChooserPath)
            put(SETTINGS_CLASSES_CHOOSER_PATH, classesChooserPath)
            put(SETTINGS_JRE_CHOOSER_PATH, jreChooserPath)
            recentMenu.save(this)
        }
    }

    private fun loadWindowSettings() {

        val preferences = Preferences.userNodeForPackage(this::class.java)
        val defaultFrameBounds = getDefaultFrameBounds()

        val windowX = preferences.getInt(SETTINGS_WINDOW_X, defaultFrameBounds.x)
        val windowY = preferences.getInt(SETTINGS_WINDOW_Y, defaultFrameBounds.y)
        val windowWidth = preferences.getInt(SETTINGS_WINDOW_WIDTH, defaultFrameBounds.width)
        val windowHeight = preferences.getInt(SETTINGS_WINDOW_HEIGHT, defaultFrameBounds.height)

        val frameBounds = Rectangle(windowX, windowY, windowWidth, windowHeight)
        // sanitize frame bounds
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val screenBounds = Rectangle(screenSize)
        frameBounds.translate(-minOf(0, frameBounds.x), -min(0, frameBounds.y))
        frameBounds.translate(-maxOf(0, frameBounds.x + frameBounds.width - screenSize.width), -max(0, frameBounds.y + frameBounds.height - screenSize.height))

        bounds = screenBounds.intersection(frameBounds)

        if (preferences.getBoolean(SETTINGS_WINDOW_MAXIMIZED, false)) {
            extendedState = Frame.MAXIMIZED_BOTH
        }

    }

    private fun getDefaultFrameBounds(): Rectangle {
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        return Rectangle(
                (screenSize.getWidth() - DEFAULT_WINDOW_WIDTH).toInt() / 2,
                (screenSize.getHeight() - DEFAULT_WINDOW_HEIGHT).toInt() / 2,
                DEFAULT_WINDOW_WIDTH,
                DEFAULT_WINDOW_HEIGHT
        )
    }

    init {
        loadSettings()
        setupMenu()
        setupEventHandlers()
        setupWindowBounds()
        setupFrame()
        updateTitle()
    }

    companion object {

        private const val SETTINGS_WORKSPACE_CHOOSER_PATH = "workspaceChooserPath"
        private const val SETTINGS_CLASSES_CHOOSER_PATH = "classesChooserPath"
        private const val SETTINGS_JRE_CHOOSER_PATH = "jreChooserPath"
        private const val DEFAULT_WINDOW_WIDTH = 800
        private const val DEFAULT_WINDOW_HEIGHT = 600
        private const val NEW_FRAME_OFFSET = 22

        private const val SETTINGS_WINDOW_WIDTH = "windowWidth"
        private const val SETTINGS_WINDOW_HEIGHT = "windowHeight"
        private const val SETTINGS_WINDOW_X = "windowX"
        private const val SETTINGS_WINDOW_Y = "windowY"
        private const val SETTINGS_WINDOW_MAXIMIZED = "windowMaximized"

        private val icons = hashMapOf<String, ImageIcon>()
        val ICON_IMAGES = listOf(16, 32, 48, 64, 128, 256).map { getIcon("jclasslib_$it.png").image }
        val multiResolutionImageConstructor = try {
            Class.forName("java.awt.image.BaseMultiResolutionImage")?.getConstructor(Array<Image>::class.java)
        } catch (t : Throwable) {
            null
        }

        fun getIcon(fileName: String): ImageIcon {
            return icons.getOrPut(fileName) {
                if (fileName.contains("jclasslib_")) {
                    ImageIcon(getImageUrl(fileName))
                } else {
                    val lowResImage = readImage(fileName)
                    val highResImage = readImage(fileName.replace(".png", "@2x.png"))
                    val images = listOfNotNull(lowResImage, highResImage).toTypedArray()
                    require(images.size == 2)
                    val combinedImage = multiResolutionImageConstructor?.let {
                        try {
                            it.newInstance(images) as Image
                        } catch (t: Throwable) {
                            null
                        }
                    } ?: images.first()
                    ImageIcon(combinedImage)
                }
            }
        }

        private fun readImage(fileName: String): BufferedImage? = getImageUrl(fileName)?.let { ImageIO.read(it) }
        private fun getImageUrl(fileName: String) = BrowserFrame::class.java.getResource("images/$fileName")
    }
}
