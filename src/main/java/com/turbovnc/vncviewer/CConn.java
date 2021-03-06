/* Copyright (C) 2002-2005 RealVNC Ltd.  All Rights Reserved.
 * Copyright 2009-2011 Pierre Ossman <ossman@cendio.se> for Cendio AB
 * Copyright (C) 2011-2012 D. R. Commander.  All Rights Reserved.
 * Copyright (C) 2011-2012 Brian P. Hinz
 *
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301,
 * USA.
 */

//
// CConn
//
// Methods on CConn are called from both the GUI thread and the thread which
// processes incoming RFB messages ("the RFB thread").  This means we need to
// be careful with synchronization here.
//
// Any access to writer() must not only be synchronized, but we must also make
// sure that the connection is in RFBSTATE_NORMAL.  We are guaranteed this for
// any code called after serverInit() has been called.  Since the DesktopWindow
// isn't created until then, any methods called only from DesktopWindow can
// assume that we are in RFBSTATE_NORMAL.

package com.turbovnc.vncviewer;

import java.awt.*;
import java.awt.event.*;

import java.io.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import javax.swing.*;
import javax.swing.ImageIcon;
import java.net.URL;
import java.util.*;

import com.turbovnc.rdr.*;
import com.turbovnc.rfb.*;
import com.turbovnc.rfb.Point;
import com.turbovnc.network.Socket;
import com.turbovnc.network.TcpSocket;

public class CConn extends CConnection implements UserPasswdGetter, UserMsgBox,
  OptionsDialogCallback, FdInStreamBlockCallback {

  public final PixelFormat getPreferredPF() { return fullColourPF; }
  static final PixelFormat VERY_LOW_COLOR_PF =
    new PixelFormat(8, 3, false, true, 1, 1, 1, 2, 1, 0);
  static final PixelFormat LOW_COLOR_PF =
    new PixelFormat(8, 6, false, true, 3, 3, 3, 4, 2, 0);
  static final PixelFormat MEDIUM_COLOR_PF =
    new PixelFormat(8, 8, false, true, 7, 7, 3, 5, 2, 0);
  static final PixelFormat HIGH_COLOR_PF =
    new PixelFormat(16, 16, false, true, 31, 63, 31, 11, 5, 0);

  static final double getTime() {
    return (double)System.nanoTime() / 1.0e9;
  }

  ////////////////////////////////////////////////////////////////////
  // The following methods are all called from the RFB thread

  public CConn(VncViewer viewer_, Socket sock_) {
    sock = sock_;  viewer = viewer_;
    opts = new Options(viewer.opts);
    benchmark = viewer.benchFile != null;
    pendingPFChange = false;
    lastServerEncoding = -1;

    formatChange = false; encodingChange = false;
    currentEncoding = opts.preferredEncoding;
    showToolbar = VncViewer.showToolbar.getValue();
    // Important to initialize because getOptions()
    // refers to clipboardDialog
    clipboardDialog = new ClipboardDialog(this);
    options = new OptionsDialog(this);
    options.initDialog();
    firstUpdate = true; pendingUpdate = false; continuousUpdates = false;
    forceNonincremental = true; supportsSyncFence = false;

    setShared(opts.shared);
    upg = this;
    msg = this;

    cp.supportsDesktopResize = true;
    cp.supportsExtendedDesktopSize = true;
    cp.supportsSetDesktopSize = true;
    cp.supportsClientRedirect = true;
    cp.supportsDesktopRename = true;
    
    // Important to initialize because getOptions()
    // refers to F8Menu
    initMenu();

    initCConn(viewer_, sock_);
  }

  public CConn(VncViewer viewer_, Socket sock_, boolean performInitialisation_) {
    sock = sock_;  viewer = viewer_;
    opts = new Options(viewer.opts);
    benchmark = viewer.benchFile != null;
    pendingPFChange = false;
    lastServerEncoding = -1;

    formatChange = false; encodingChange = false;
    currentEncoding = opts.preferredEncoding;
    showToolbar = VncViewer.showToolbar.getValue();
    // Important to initialize because getOptions()
    // refers to clipboardDialog
    clipboardDialog = new ClipboardDialog(this);
    options = new OptionsDialog(this);
    options.initDialog();
    firstUpdate = true; pendingUpdate = false; continuousUpdates = false;
    forceNonincremental = true; supportsSyncFence = false;

    setShared(opts.shared);
    upg = this;
    msg = this;

    cp.supportsDesktopResize = true;
    cp.supportsExtendedDesktopSize = true;
    cp.supportsSetDesktopSize = true;
    cp.supportsClientRedirect = true;
    cp.supportsDesktopRename = true;
    
    // Important to initialize because getOptions()
    // refers to F8Menu
    initMenu();

    if (performInitialisation_)
        initCConn(viewer_, sock_);
  }

  // Renaming CConn constructor to "initCConn"
  // so that it can be called by multiple constructors.
  //public CConn(VncViewer viewer_, Socket sock_) {
  public void initCConn(VncViewer viewer_, Socket sock_) {

    if (sock != null) {
      String name = sock.getPeerEndpoint();
      vlog.info("Accepted connection from " + name);
    } else if (!benchmark) {
      if (opts.serverName != null &&
          !VncViewer.alwaysShowConnectionDialog.getValue()) {
        opts.port = Hostname.getPort(opts.serverName);
        opts.serverName = Hostname.getHost(opts.serverName);
      } else {
        ServerDialog dlg = new ServerDialog(options, opts, this);
        boolean ret = dlg.showDialog();
        if (!ret) {
          close();
          return;
        }
      }

      sock = new TcpSocket(opts.serverName, opts.port);
      vlog.info("connected to host " + opts.serverName + " port " +
                opts.port);
    }

    if (benchmark) {
      state_ = RFBSTATE_INITIALISATION;
      reader_ = new CMsgReaderV3(this, viewer.benchFile);
    } else {
      sock.inStream().setBlockCallback(this);
      setServerName(opts.serverName);
      setStreams(sock.inStream(), sock.outStream());
      initialiseProtocol();
    }
  }

  public void reset() {
    if (reader_ != null)
      reader_.reset();
    state_ = RFBSTATE_INITIALISATION;
  }

  public void refreshFramebuffer() {
    forceNonincremental = true;

    // Without fences, we cannot safely trigger an update request directly
    // but must wait for the next update to arrive.
    if (supportsSyncFence)
      requestNewUpdate();
  }

  public boolean showMsgBox(int flags, String title, String text) {
    //StringBuffer titleText = new StringBuffer("VNC Viewer: "+title);
    return true;
  }

  // deleteWindow() is called when the user closes the desktop or menu windows.

  void deleteWindow() {
    if (viewport != null)
      viewport.dispose();
    viewport = null;
  }

  // blockCallback() is called when reading from the socket would block.
  public void blockCallback() {
    try {
      synchronized(this) {
        wait(0, 50000);
      }
    } catch(InterruptedException e) {
      throw new SystemException(e.toString());
    }
  }

  // getUserPasswd() is called by the CSecurity object when it needs us to read
  // a password from the user.

  public final boolean getUserPasswd(StringBuffer user, StringBuffer passwd) {
    String title = ((user == null ? "Standard VNC Authentication" :
                                    "Unix Login Authentication") +
                    " [" + csecurity.description() + "]");
    String passwordFileStr = VncViewer.passwordFile.getValue();
    PasswdDialog dlg = null;
    String autoPass;

    if (VncViewer.autoPass.getValue()) {
      BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
      try {
        autoPass = in.readLine();
      } catch(IOException e) {
        throw new SystemException(e.toString());
      }
      VncViewer.autoPass.setParam("0");
    } else
      autoPass = VncViewer.password.getValue();

    if (autoPass != null && passwd != null) {
      passwd.append(autoPass);
      passwd.setLength(autoPass.length());
      VncViewer.password.setParam(null);
    }

    if (user == null && passwordFileStr != null && autoPass == null) {
      InputStream fp = null;
      try {
        fp = new FileInputStream(passwordFileStr);
      } catch(FileNotFoundException e) {
        throw new ErrorException("Could not open password file");
      }
      byte[] obfPwd = new byte[256];
      try {
        fp.read(obfPwd);
        fp.close();
      } catch(IOException e) {
        throw new ErrorException("Could not read password file");
      }
      String plainPasswd = VncAuth.unobfuscatePasswd(obfPwd);
      passwd.append(plainPasswd);
      passwd.setLength(plainPasswd.length());
      return true;
    }

    if (user == null) {
      if (autoPass == null)
        dlg = new PasswdDialog(title, (user == null), null, (passwd == null));
    } else {
      String userName = opts.user;
      if (opts.sendLocalUsername) {
        userName = (String)System.getProperties().get("user.name");
        if (passwd == null)
          return true;
      }
      if (autoPass == null)
        dlg = new PasswdDialog(title, (userName != null), userName,
                               (passwd == null));
      else
        user.append(userName);
    }

    if (dlg != null) {
      if (!dlg.showDialog()) return false;
      if (user != null)
        user.append(dlg.userEntry.getText());
      if (passwd != null)
        passwd.append(dlg.passwdEntry.getPassword());
    }

    return true;
  }

  // CConnection callback methods

  // serverInit() is called when the serverInit message has been received.  At
  // this point we create the desktop window and display it.  We also tell the
  // server the pixel format and encodings to use and request the first update.
  public void serverInit() {
    super.serverInit();

    serverPF = cp.pf();

    desktop = new DesktopWindow(cp.width, cp.height, serverPF, this);
    fullColourPF = desktop.getPreferredPF();

    // Force a switch to the format and encoding we'd like
    formatChange = true; encodingChange = true;

    // And kick off the update cycle
    if (!benchmark)
      requestNewUpdate();
    else {
      if (opts.colors < 0) {
        pendingPF = fullColourPF;
      } else {
        if (opts.colors == 8) {
          pendingPF = VERY_LOW_COLOR_PF;
        } else if (opts.colors == 64) {
          pendingPF = LOW_COLOR_PF;
        } else if (opts.colors == 256) {
          pendingPF = MEDIUM_COLOR_PF;
        } else {
          pendingPF = HIGH_COLOR_PF;
        }
      }
      pendingPFChange = true;
    }

    // This initial update request is a bit of a corner case, so we need
    // to help out setting the correct format here.
    assert(pendingPFChange);
    desktop.setServerPF(pendingPF);
    cp.setPF(pendingPF);
    pendingPFChange = false;

    recreateViewport();
  }

  // setDesktopSize() is called when the desktop size changes (including when
  // it is set initially).
  public void setDesktopSize(int w, int h) {
    super.setDesktopSize(w, h);
    resizeFramebuffer();
  }

  // setExtendedDesktopSize() is a more advanced version of setDesktopSize()
  public void setExtendedDesktopSize(int reason, int result, int w, int h,
                                     ScreenSet layout) {
    super.setExtendedDesktopSize(reason, result, w, h, layout);

    if ((reason == screenTypes.reasonClient) &&
        (result != screenTypes.resultSuccess)) {
      vlog.error("SetDesktopSize failed: " + result);
      return;
    }

    resizeFramebuffer();
  }

  // clientRedirect() migrates the client to another host/port
  public void clientRedirect(int port, String host,
                             String x509subject) {
    sock.close();
    setServerPort(port);
    sock = new TcpSocket(host, port);
    vlog.info("Redirected to " + host + ":" + port);
    VncViewer.newViewer(viewer, sock, true);
  }

  // setName() is called when the desktop name changes
  public void setName(String name) {
    super.setName(name);

    if (viewport != null) {
      viewport.setTitle(name + " - TurboVNC");
    }
  }

  // framebufferUpdateStart() is called at the beginning of an update.
  // Here we try to send out a new framebuffer update request so that the
  // next update can be sent out in parallel with us decoding the current
  // one.
  public void framebufferUpdateStart() {
    // Note: This might not be true if sync fences are supported
    pendingUpdate = false;

    if (!benchmark) requestNewUpdate();
  }

  // framebufferUpdateEnd() is called at the end of an update.
  // For each rectangle, the FdInStream will have timed the speed
  // of the connection, allowing us to select format and encoding
  // appropriately, and then request another incremental update.
  public void framebufferUpdateEnd() {

    desktop.updateWindow();

    if (firstUpdate) {
      int width, height;

      // We need fences to make extra update requests and continuous
      // updates "safe". See fence() for the next step.
      if (cp.supportsFence)
        writer().writeFence(
          fenceTypes.fenceFlagRequest | fenceTypes.fenceFlagSyncNext, 0, null);

      if (cp.supportsSetDesktopSize &&
          VncViewer.desktopSize.getValueStr() != null &&
          VncViewer.desktopSize.getValueStr().split("x").length == 2) {
        width = Integer.parseInt(VncViewer.desktopSize.getValue().split("x")[0]);
        height = Integer.parseInt(VncViewer.desktopSize.getValue().split("x")[1]);
        ScreenSet layout;

        layout = cp.screenLayout;

        if (layout.numScreens() == 0)
          layout.addScreen(new Screen());
        else if (layout.numScreens() != 1) {

          while (true) {
            Iterator<Screen> iter = layout.screens.iterator();
            Screen screen = (Screen)iter.next();

            if (!iter.hasNext())
              break;

            layout.removeScreen(screen.id);
          }
        }

        Screen screen0 = (Screen)layout.screens.iterator().next();
        screen0.dimensions.tl.x = 0;
        screen0.dimensions.tl.y = 0;
        screen0.dimensions.br.x = width;
        screen0.dimensions.br.y = height;

        writer().writeSetDesktopSize(width, height, layout);
      }

      firstUpdate = false;
    }

    // A format change has been scheduled and we are now past the update
    // with the old format. Time to active the new one.
    if (pendingPFChange) {
      desktop.setServerPF(pendingPF);
      cp.setPF(pendingPF);
      pendingPFChange = false;
    }
  }

  // The rest of the callbacks are fairly self-explanatory...

  public void setColourMapEntries(int firstColour, int nColours, int[] rgbs) {
    desktop.setColourMapEntries(firstColour, nColours, rgbs);
  }

  public void bell() {
    if (opts.acceptBell)
      desktop.getToolkit().beep();
  }

  public void serverCutText(String str, int len) {
    if (opts.acceptClipboard)
      clipboardDialog.serverCutText(str, len);
  }

  public void startDecodeTimer() {
    if (benchmark) {
      tDecodeStart = getTime();
      tReadOld = viewer.benchFile.getReadTime();
    }
  }

  public void stopDecodeTimer() {
    if (benchmark)
      tDecode += getTime() - tDecodeStart -
                 (viewer.benchFile.getReadTime() - tReadOld);
  }

  // We start timing on beginRect and stop timing on endRect, to
  // avoid skewing the bandwidth estimation as a result of the server
  // being slow or the network having high latency
  public void beginRect(Rect r, int encoding) {
    if (!benchmark)
      sock.inStream().startTiming();
    if (encoding != Encodings.encodingCopyRect) {
      boolean updateTitle = false;
      if (encoding != lastServerEncoding)
        updateTitle = true;
      lastServerEncoding = encoding;
      if (updateTitle && viewport != null)
        viewport.updateTitle();
    }
  }

  public void endRect(Rect r, int encoding) {
    if (!benchmark)
      sock.inStream().stopTiming();
  }

  public void fillRect(Rect r, int p) {
    desktop.fillRect(r.tl.x, r.tl.y, r.width(), r.height(), p);
  }

  public void imageRect(Rect r, Object p) {
    desktop.imageRect(r.tl.x, r.tl.y, r.width(), r.height(), p);
  }

  public void copyRect(Rect r, int sx, int sy) {
    double tBlitStart = getTime();
    desktop.copyRect(r.tl.x, r.tl.y, r.width(), r.height(), sx, sy);
    tBlit += getTime() - tBlitStart;
  }

  public Object getRawPixelsRW(int[] stride) {
    return desktop.getRawPixelsRW(stride);
  }

  public void releaseRawPixels(Rect r) {
    desktop.releaseRawPixels(r);
  }

  public void setCursor(int width, int height, Point hotspot,
                        int[] data, byte[] mask) {
    if (viewport != null && (viewport.dx > 0 || viewport.dy > 0))
      hotspot.translate(new Point(viewport.dx, viewport.dy));
    desktop.setCursor(width, height, hotspot, data, mask);
  }

  public void fence(int flags, int len, byte[] data) {
    // can't call super.super.fence(flags, len, data);
    cp.supportsFence = true;

    if ((flags & fenceTypes.fenceFlagRequest) != 0) {
      // We handle everything synchronously so we trivially honor these modes
      flags = flags & (fenceTypes.fenceFlagBlockBefore | fenceTypes.fenceFlagBlockAfter);

      writer().writeFence(flags, len, data);
      return;
    }

    if (len == 0) {
      // Initial probe
      if ((flags & fenceTypes.fenceFlagSyncNext) != 0) {
        supportsSyncFence = true;

        if (cp.supportsContinuousUpdates) {
          vlog.info("Enabling continuous updates");
          continuousUpdates = true;
          writer().writeEnableContinuousUpdates(true, 0, 0, cp.width,
                                                cp.height);
        }
      }
    } else {
      // Pixel format change
      MemInStream memStream = new MemInStream(data, 0, len);
      PixelFormat pf = new PixelFormat();

      pf.read(memStream);

      desktop.setServerPF(pf);
      cp.setPF(pf);
    }
  }

  private void resizeFramebuffer() {
    if (desktop == null)
      return;

    if (continuousUpdates)
      writer().writeEnableContinuousUpdates(true, 0, 0, cp.width, cp.height);

    if ((cp.width == 0) && (cp.height == 0))
      return;
    if ((desktop.width() == cp.width) && (desktop.height() == cp.height))
      return;

    desktop.resize();
    recreateViewport();
  }

  // recreateViewport() recreates our top-level window.  This seems to be
  // better than attempting to resize the existing window, at least with
  // various X window managers.

  static Rectangle savedRect = new Rectangle(-1, -1, 0, 0);
  static int savedState = -1;

  private void recreateViewport() { recreateViewport(false); }

  private void recreateViewport(boolean restore) {
    if (viewport != null) {
      if (opts.fullScreen) {
        savedState = viewport.getExtendedState();
        viewport.setExtendedState(JFrame.NORMAL);
        savedRect = viewport.getBounds();
      }
      viewport.dispose();
    }
    viewport = new Viewport(this);
    viewport.setUndecorated(opts.fullScreen);
    desktop.setViewport(viewport);
    ClassLoader loader = this.getClass().getClassLoader();
    URL url = loader.getResource("com/turbovnc/vncviewer/turbovnc-sm.png");
    ImageIcon icon = null;
    if (url != null) {
      icon = new ImageIcon(url);
      viewport.setIconImage(icon.getImage());
    }
    reconfigureViewport(restore);
    if ((cp.width > 0) && (cp.height > 0))
      viewport.setVisible(true);
    desktop.requestFocusInWindow();
  }

  private Rectangle getSpannedSize(boolean fullScreen) {
    GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
    GraphicsDevice[] gsList = ge.getScreenDevices();
    Rectangle primary = null, s0 = null;
    Rectangle span = new Rectangle(-1, -1, 0, 0);
    Insets in = new Insets(0, 0, 0, 0);
    int tLeft = 0, tTop = 0, tRight = 0, tBottom = 0;
    boolean equal = true;
    int sw = desktop.scaledWidth;
    int sh = desktop.scaledHeight;

    if (opts.scalingFactor == Options.SCALE_AUTO ||
        opts.scalingFactor == Options.SCALE_FIXEDRATIO) {
      sw = cp.width;
      sh = cp.height;
    }

    Toolkit tk = Toolkit.getDefaultToolkit();

    for (GraphicsDevice gs : gsList) {
      GraphicsConfiguration[] gcList = gs.getConfigurations();
      for (GraphicsConfiguration gc : gcList) {
        Rectangle s = gc.getBounds();
        if (!fullScreen) {
          if (gc == gcList[0])
            in = tk.getScreenInsets(gc);
          s.setBounds(s.x + in.left, s.y + in.top,
                      s.width - in.left - in.right,
                      s.height - in.top - in.bottom);
        }
        if (s0 == null) {
          s0 = s;
          span.setBounds(s);
          tLeft = s.x;  tTop = s.y;
          tRight = s.x + s.width;  tBottom = s.y + s.height;
        }
        if (primary == null || (gc == gcList[0] &&
                                (s.x < primary.x || s.y < primary.y))) {
          primary = s;
        }

        tLeft = Math.min(tLeft, s.x);
        tRight = Math.max(tRight, s.x + s.width);
        tTop = Math.min(tTop, s.y);
        tBottom = Math.max(tBottom, s.y + s.height);

        // If any monitors aren't equal in resolution to and evenly offset from
        // the primary, then we can't use the simple path.
        if (s.width != s0.width ||
            s.height != s0.height ||
            (Math.abs(s.y - s0.y) % s0.height) != 0 ||
            (Math.abs(s.x - s0.x) % s0.width) != 0)
            equal = false;

        // If the screen areas of the primary monitor and this monitor overlap
        // vertically, then allow the full-screen window to extend horizontally
        // to this monitor, and constrain it vertically, if necessary, to fit
        // within this monitor's dimensions.
        if (Math.min(s.y + s.height, s0.y + s0.height) -
            Math.max(s.y, s0.y) > 0) {
          int right = Math.max(s.x + s.width, span.x + span.width);
          int bottom = Math.min(s.y + s.height, span.y + span.height);
          span.x = Math.min(s.x, span.x);
          span.y = Math.max(s.y, span.y);
          span.width = right - span.x;
          span.height = bottom - span.y;
        }
      }
    }

    if (opts.span == Options.SPAN_PRIMARY ||
        (opts.span == Options.SPAN_AUTO &&
         (sw <= primary.width || span.width <= primary.width) &&
         (sh <= primary.height || span.height <= primary.height)))
      return primary;
    else {
      if (equal && fullScreen)
        return new Rectangle(tLeft, tTop, tRight - tLeft, tBottom - tTop);
      else
        return span;
    }
  }

  // Resize non-full-screen window based on the spanning option
  public void sizeWindow() {
    boolean pack = true;
    int w = desktop.scaledWidth;
    int h = desktop.scaledHeight;
    Rectangle span = getSpannedSize(false);

    if (opts.fullScreen) return;

    if (opts.scalingFactor == Options.SCALE_AUTO ||
        opts.scalingFactor == Options.SCALE_FIXEDRATIO) {
      w = cp.width;
      h = cp.height;
      pack = false;
    }

    if (w >= span.width) {
      w = span.width;
      pack = false;
    }
    if (h >= span.height) {
      h = span.height;
      pack = false;
    }

    viewport.setExtendedState(JFrame.NORMAL);
    int x = (span.width - w) / 2;
    int y = (span.height - h) / 2;
    viewport.setGeometry(x, y, w, h, pack);
  }

  private void reconfigureViewport() { reconfigureViewport(false); }

  private void reconfigureViewport(boolean restore) {
    desktop.setScaledSize();
    if (opts.fullScreen) {
      // NOTE: We have to use the work area on OS X, because there is no way
      // to hide the menu bar in full-screen mode.
      Rectangle span = getSpannedSize(!VncViewer.os.startsWith("mac os x"));
      viewport.setExtendedState(JFrame.NORMAL);
      viewport.setGeometry(span.x, span.y, span.width,
                           span.height, false);
      viewport.setAlwaysOnTop(true);
    } else {
      if (savedRect.width > 0 && savedRect.height > 0 && restore) {
        if (savedState >= 0)
          viewport.setExtendedState(savedState);
        viewport.setGeometry(savedRect.x, savedRect.y, savedRect.width,
                             savedRect.height, false);
      } else {
        sizeWindow();
      }
      viewport.setAlwaysOnTop(false);
    }
  }

  // requestNewUpdate() requests an update from the server, having set the
  // format and encoding appropriately.
  private void requestNewUpdate() {
    if (formatChange) {
      PixelFormat pf;

      /* Catch incorrect requestNewUpdate calls */
      assert(!pendingUpdate || supportsSyncFence);

      if (opts.colors < 0) {
        pf = fullColourPF;
      } else {
        if (opts.colors == 8) {
          pf = VERY_LOW_COLOR_PF;
        } else if (opts.colors == 64) {
          pf = LOW_COLOR_PF;
        } else if (opts.colors == 256) {
          pf = MEDIUM_COLOR_PF;
        } else {
          pf = HIGH_COLOR_PF;
        }
      }

      if (supportsSyncFence) {
        // We let the fence carry the pixel format and switch once we
        // get the response back. That way we will be synchronised with
        // when the server switches.
        MemOutStream memStream = new MemOutStream();

        pf.write(memStream);

        writer().writeFence(fenceTypes.fenceFlagRequest | fenceTypes.fenceFlagSyncNext,
                            memStream.length(), (byte[])memStream.data());
      } else {
        // New requests are sent out at the start of processing the last
        // one, so we cannot switch our internal format right now (doing so
        // would mean misdecoding the current update).
        pendingPFChange = true;
        pendingPF = pf;
      }

      String str = pf.print();
      vlog.info("Using pixel format " + str);
      writer().writeSetPixelFormat(pf);

      formatChange = false;
    }

    checkEncodings();

    if (forceNonincremental || !continuousUpdates) {
      pendingUpdate = true;
      writer().writeFramebufferUpdateRequest(new Rect(0, 0, cp.width, cp.height),
                                             !formatChange && !forceNonincremental);
    }

    forceNonincremental = false;
  }


  ////////////////////////////////////////////////////////////////////
  // The following methods are all called from the GUI thread

  // close() shuts down the socket, thus waking up the RFB thread.
  public void close() {
    deleteWindow();
    shuttingDown = true;
    if (sock != null)
      sock.shutdown();
  }

  // Menu callbacks.  These are guaranteed only to be called after serverInit()
  // has been called, since the menu is only accessible from the DesktopWindow

  private void initMenu() {
    menu = new F8Menu(this);
  }

  void showMenu(int x, int y) {
    String os = System.getProperty("os.name");
    if (os.startsWith("Windows"))
      com.sun.java.swing.plaf.windows.WindowsLookAndFeel.setMnemonicHidden(false);
    if (viewport != null && (viewport.dx > 0 || viewport.dy > 0)) {
      x += viewport.dx;
      y += viewport.dy;
    }
    menu.show(desktop, x, y);
  }

  void showAbout() {
    InputStream stream = cl.getResourceAsStream("com/turbovnc/vncviewer/timestamp");
    String pkgDate = "";
    String pkgTime = "";
    try {
      Manifest manifest = new Manifest(stream);
      Attributes attributes = manifest.getMainAttributes();
      pkgDate = attributes.getValue("Package-Date");
      pkgTime = attributes.getValue("Package-Time");
    } catch(IOException e) { }
    JOptionPane.showMessageDialog((viewport != null ? viewport : null),
      VncViewer.PRODUCT_NAME + " v" + VncViewer.version +
        " (" + VncViewer.build + ") " +
        "[JVM: " + System.getProperty("os.arch") + "]\n" +
      "Built on " + pkgDate + " at " + pkgTime + "\n" +
      "Copyright (C) " + VncViewer.copyrightYear + " " + VncViewer.copyright +
        "\n" +
      VncViewer.url,
      "About TurboVNC Viewer", JOptionPane.INFORMATION_MESSAGE, logo);
  }

  void showInfo() {
    JOptionPane.showMessageDialog(viewport,
      "Desktop name:  " + cp.name() + "\n" +
      "Host:  " + sock.getPeerName() + ":" + sock.getPeerPort() + "\n" +
      "Size:  " + cp.width + "x" + cp.height + "\n" +
      "Pixel format:  " + desktop.getPF().print() + "\n" +
      "(server default " + serverPF.print() + ")\n" +
      "Requested encoding:  " + Encodings.encodingName(currentEncoding) +
        "\n" +
      "Last used encoding:  " + Encodings.encodingName(lastServerEncoding) +
        "\n" +
      "Line speed estimate:  " + sock.inStream().kbitsPerSecond() + " kbit/s" +
        "\n" +
      "Protocol version:  " + cp.majorVersion + "." + cp.minorVersion + "\n" +
      "Security method:  " + Security.secTypeName(csecurity.getType()) +
        " [" + csecurity.description() + "]\n" +
      "JPEG decompression:  " +
        (reader_.isTurboJPEG() ? "Turbo" : "Unaccelerated"),
      "VNC connection info", JOptionPane.PLAIN_MESSAGE);
  }

  public void refresh() {
    writer().writeFramebufferUpdateRequest(new Rect(0, 0, cp.width, cp.height),
                                           false);
    pendingUpdate = true;
  }

  public void losslessRefresh() {
    int currentEncodingSave = currentEncoding;
    int compressLevelSave = opts.compressLevel;
    int qualitySave = opts.quality;
    boolean allowJpegSave = opts.allowJpeg;
    boolean alreadyLossless = false;

    if (currentEncoding == Encodings.encodingTight &&
        opts.compressLevel == 1 && opts.quality == -1 && !opts.allowJpeg)
      alreadyLossless = true;

    if (!alreadyLossless) {
      currentEncoding = Encodings.encodingTight;
      opts.compressLevel = 1;
      opts.quality = -1;
      opts.allowJpeg = false;
      encodingChange = true;
      checkEncodings();
    }
    refresh();
    if (!alreadyLossless) {
      currentEncoding = currentEncodingSave;
      opts.compressLevel = compressLevelSave;
      opts.quality = qualitySave;
      opts.allowJpeg = allowJpegSave;
      encodingChange = true;
      checkEncodings();
    }
  }


  // OptionsDialogCallback.  setOptions() sets the options dialog's checkboxes
  // etc to reflect our flags.  getOptions() sets our flags according to the
  // options dialog's checkboxes.  They are both called from the GUI thread.
  // Some of the flags are also accessed by the RFB thread.  I believe that
  // reading and writing boolean and int values in java is atomic, so there is
  // no need for synchronization.

  public boolean isUnixLoginSelected() {
    return options.secUnixLogin.isSelected();
  }

  public boolean isUnixLoginForced() {
    return (opts.user != null || opts.sendLocalUsername);
  }

  public void setTightOptions() {
    int encoding = currentEncoding;
    if (lastServerEncoding != Encodings.encodingTight &&
        lastServerEncoding >= 0)
      encoding = lastServerEncoding;

    if (encoding != Encodings.encodingTight) {
      options.allowJpeg.setEnabled(false);
      options.subsamplingLevel.setEnabled(false);
      options.subsamplingLabel.setEnabled(false);
      options.subsamplingLabelLo.setEnabled(false);
      options.subsamplingLabelHi.setEnabled(false);
      options.jpegQualityLevel.setMinimum(0);
      options.jpegQualityLevel.setMaximum(9);
      options.jpegQualityLevel.setMajorTickSpacing(1);
      options.jpegQualityLevel.setMinorTickSpacing(0);
      options.jpegQualityLevel.setSnapToTicks(true);
      options.jpegQualityLevel.setEnabled(true);
      options.jpegQualityLabelString = new String("Image quality level: ");
      options.jpegQualityLabel.setText(options.jpegQualityLabelString +
        options.jpegQualityLevel.getValue());
      options.jpegQualityLabel.setEnabled(true);
      options.jpegQualityLabelLo.setEnabled(true);
      options.jpegQualityLabelHi.setEnabled(true);
      options.encMethodComboBox.setEnabled(false);
      if (options.encMethodComboBox.getItemCount() > 5)
        options.encMethodComboBox.removeItemAt(5);
      options.encMethodComboBox.insertItemAt(Encodings.encodingName(encoding), 5);
      options.encMethodComboBox.setSelectedItem(Encodings.encodingName(encoding));
      options.encMethodLabel.setText("Encoding type:");
      options.encMethodLabel.setEnabled(false);
    }
    if (encoding != Encodings.encodingTight ||
        VncViewer.compressLevel.getValue() > 1) {
      options.compressionLevel.setMaximum(9);
      options.compressionLevel.setEnabled(true);
      options.compressionLabel.setEnabled(true);
      options.compressionLabelLo.setEnabled(true);
      options.compressionLabelHi.setEnabled(true);
      options.compressionLabelString = new String("Compression level: ");
      options.compressionLabel.setText(options.compressionLabelString +
        options.compressionLevel.getValue());
    }
  }

  public void setOptions() {
    options.allowJpeg.setSelected(opts.allowJpeg);
    options.subsamplingLevel.setValue(opts.getSubsamplingOrdinal());
    options.jpegQualityLevel.setValue(opts.quality);
    options.compressionLevel.setValue(opts.compressLevel);

    setTightOptions();

    options.viewOnly.setSelected(opts.viewOnly);
    options.acceptClipboard.setSelected(opts.acceptClipboard);
    options.sendClipboard.setSelected(opts.sendClipboard);
    options.menuKey.setSelectedItem(KeyEvent.getKeyText(MenuKey.getMenuKeyCode()));

    if (state() == RFBSTATE_NORMAL) {
      options.shared.setEnabled(false);
      options.secVeNCrypt.setEnabled(false);
      options.encNone.setEnabled(false);
      options.encTLS.setEnabled(false);
      options.encX509.setEnabled(false);
      options.ca.setEnabled(false);
      options.crl.setEnabled(false);
      options.secIdent.setEnabled(false);
      options.secNone.setEnabled(false);
      options.secVnc.setEnabled(false);
      options.secUnixLogin.setEnabled(false);
      options.secPlain.setEnabled(false);
      options.sendLocalUsername.setEnabled(false);
    } else {
      options.shared.setSelected(opts.shared);
      options.sendLocalUsername.setSelected(opts.sendLocalUsername);
      options.secUnixLogin.setSelected(!opts.noUnixLogin);

      /* Process non-VeNCrypt sectypes */
      java.util.List<Integer> secTypes = new ArrayList<Integer>();
      secTypes = Security.getEnabledSecTypes();
      boolean enableVeNCrypt = false;
      for (Iterator<Integer> i = secTypes.iterator(); i.hasNext();) {
        switch ((Integer)i.next()) {
        case Security.secTypeVeNCrypt:
          enableVeNCrypt = true;
          break;
        case Security.secTypeNone:
          options.secNone.setSelected(true);
          break;
        case Security.secTypeVncAuth:
          options.secVnc.setSelected(true);
          break;
        }
      }

      /* Process VeNCrypt subtypes */
      if (enableVeNCrypt) {
        java.util.List<Integer> secTypesExt = new ArrayList<Integer>();
        secTypesExt = Security.getEnabledExtSecTypes();
        for (Iterator<Integer> iext = secTypesExt.iterator(); iext.hasNext();) {
          switch ((Integer)iext.next()) {
          case Security.secTypePlain:
            options.secVeNCrypt.setSelected(true);
            options.encNone.setSelected(true);
            options.secPlain.setSelected(true);
            break;
          case Security.secTypeIdent:
            options.secVeNCrypt.setSelected(true);
            options.encNone.setSelected(true);
            options.secIdent.setSelected(true);
            break;
          case Security.secTypeTLSNone:
            options.secVeNCrypt.setSelected(true);
            options.encTLS.setSelected(true);
            options.secNone.setSelected(true);
            break;
          case Security.secTypeTLSVnc:
            options.secVeNCrypt.setSelected(true);
            options.encTLS.setSelected(true);
            options.secVnc.setSelected(true);
            break;
          case Security.secTypeTLSPlain:
            options.secVeNCrypt.setSelected(true);
            options.encTLS.setSelected(true);
            options.secPlain.setSelected(true);
            break;
          case Security.secTypeTLSIdent:
            options.secVeNCrypt.setSelected(true);
            options.encTLS.setSelected(true);
            options.secIdent.setSelected(true);
            break;
          case Security.secTypeX509None:
            options.secVeNCrypt.setSelected(true);
            options.encX509.setSelected(true);
            options.secNone.setSelected(true);
            break;
          case Security.secTypeX509Vnc:
            options.secVeNCrypt.setSelected(true);
            options.encX509.setSelected(true);
            options.secVnc.setSelected(true);
            break;
          case Security.secTypeX509Plain:
            options.secVeNCrypt.setSelected(true);
            options.encX509.setSelected(true);
            options.secPlain.setSelected(true);
            break;
          case Security.secTypeX509Ident:
            options.secVeNCrypt.setSelected(true);
            options.encX509.setSelected(true);
            options.secIdent.setSelected(true);
            break;
          }
        }
      }
      if (!options.secVeNCrypt.isSelected()) {
        options.encNone.setEnabled(false);
        options.encTLS.setEnabled(false);
        options.encX509.setEnabled(false);
        options.ca.setEnabled(false);
        options.crl.setEnabled(false);
        options.secIdent.setEnabled(false);
        options.secPlain.setEnabled(false);
      }
      options.sendLocalUsername.setEnabled(
        (options.secIdent.isSelected() && options.secIdent.isEnabled()) ||
        (options.secPlain.isSelected() && options.secPlain.isEnabled()) ||
        (options.secUnixLogin.isSelected() && options.secUnixLogin.isEnabled()));
    }

    options.fullScreen.setSelected(opts.fullScreen);
    options.span.setSelectedIndex(opts.span);
    options.cursorShape.setSelected(opts.cursorShape);
    options.acceptBell.setSelected(opts.acceptBell);
    options.showToolbar.setSelected(VncViewer.showToolbar.getValue());
    if (opts.scalingFactor == Options.SCALE_AUTO) {
      options.scalingFactor.setSelectedItem("Auto");
    } else if (opts.scalingFactor == Options.SCALE_FIXEDRATIO) {
      options.scalingFactor.setSelectedItem("Fixed Aspect Ratio");
    } else {
      options.scalingFactor.setSelectedItem(opts.scalingFactor + "%");
      if (desktop != null)
        desktop.setScaledSize();
    }
  }

  public void getOptions() {
    if (opts.allowJpeg != options.allowJpeg.isSelected())
      encodingChange = true;
    opts.allowJpeg = options.allowJpeg.isSelected();

    if (opts.quality != options.jpegQualityLevel.getValue())
      encodingChange = true;
    opts.quality = options.jpegQualityLevel.getValue();

    if (opts.compressLevel != options.compressionLevel.getValue())
      encodingChange = true;
    opts.compressLevel = options.compressionLevel.getValue();

    if (opts.subsampling != options.getSubsamplingLevel())
      encodingChange = true;
    opts.subsampling = options.getSubsamplingLevel();

    opts.sendLocalUsername = options.sendLocalUsername.isSelected();
    opts.viewOnly = options.viewOnly.isSelected();
    opts.acceptClipboard = options.acceptClipboard.isSelected();
    opts.sendClipboard = options.sendClipboard.isSelected();
    opts.acceptBell = options.acceptBell.isSelected();
    VncViewer.showToolbar.setParam(options.showToolbar.isSelected());

    int oldScalingFactor = opts.scalingFactor;
    opts.setScalingFactor(options.scalingFactor.getSelectedItem().toString());
    if (desktop != null && opts.scalingFactor != oldScalingFactor &&
      options.fullScreen.isSelected() == opts.fullScreen)
      recreateViewport();

    int index = options.span.getSelectedIndex();
    if (index >= 0 && index < Options.NUMSPANOPT)
      opts.span = index;

    clipboardDialog.setSendingEnabled(opts.sendClipboard);
    VncViewer.menuKey.setParam(
      MenuKey.getMenuKeySymbols()[options.menuKey.getSelectedIndex()].name);
    F8Menu.f8.setText("Send " + KeyEvent.getKeyText(MenuKey.getMenuKeyCode()));

    opts.shared = options.shared.isSelected();
    setShared(opts.shared);
    if (opts.cursorShape != options.cursorShape.isSelected()) {
      opts.cursorShape = options.cursorShape.isSelected();
      encodingChange = true;
      if (desktop != null)
        desktop.resetLocalCursor();
    }

    checkEncodings();

    if (state() != RFBSTATE_NORMAL) {
      Security.disableSecType(Security.secTypeNone);
      Security.disableSecType(Security.secTypeVncAuth);
      Security.disableSecType(Security.secTypePlain);
      Security.disableSecType(Security.secTypeIdent);
      Security.disableSecType(Security.secTypeTLSNone);
      Security.disableSecType(Security.secTypeTLSVnc);
      Security.disableSecType(Security.secTypeTLSPlain);
      Security.disableSecType(Security.secTypeTLSIdent);
      Security.disableSecType(Security.secTypeX509None);
      Security.disableSecType(Security.secTypeX509Vnc);
      Security.disableSecType(Security.secTypeX509Plain);
      Security.disableSecType(Security.secTypeX509Ident);

      /* Process security types which don't use encryption */
      if (options.encNone.isSelected() || !options.secVeNCrypt.isSelected()) {
        if (options.secNone.isSelected())
          Security.enableSecType(Security.secTypeNone);

        if (options.secVnc.isSelected())
          Security.enableSecType(Security.secTypeVncAuth);
      }

      if (options.encNone.isSelected() && options.secVeNCrypt.isSelected()) {
        if (options.secPlain.isSelected())
          Security.enableSecType(Security.secTypePlain);

        if (options.secIdent.isSelected())
          Security.enableSecType(Security.secTypeIdent);
      }

      /* Process security types which use TLS encryption */
      if (options.encTLS.isSelected() && options.secVeNCrypt.isSelected()) {
        if (options.secNone.isSelected())
          Security.enableSecType(Security.secTypeTLSNone);

        if (options.secVnc.isSelected())
          Security.enableSecType(Security.secTypeTLSVnc);

        if (options.secPlain.isSelected())
          Security.enableSecType(Security.secTypeTLSPlain);

        if (options.secIdent.isSelected())
          Security.enableSecType(Security.secTypeTLSIdent);
      }

      /* Process security types which use X509 encryption */
      if (options.encX509.isSelected() && options.secVeNCrypt.isSelected()) {
        if (options.secNone.isSelected())
          Security.enableSecType(Security.secTypeX509None);

        if (options.secVnc.isSelected())
          Security.enableSecType(Security.secTypeX509Vnc);

        if (options.secPlain.isSelected())
          Security.enableSecType(Security.secTypeX509Plain);

        if (options.secIdent.isSelected())
          Security.enableSecType(Security.secTypeX509Ident);
      }
    }
    if (options.fullScreen.isSelected() != opts.fullScreen)
      toggleFullScreen();
  }

  public void toggleToolbar() {
    if (viewport == null)
      return;
    showToolbar = !showToolbar;
    if (!opts.fullScreen)
      recreateViewport();
    viewport.showToolbar(showToolbar);
    menu.showToolbar.setSelected(showToolbar);
  }

  public void toggleFullScreen() {
    opts.fullScreen = !opts.fullScreen;
    menu.fullScreen.setSelected(opts.fullScreen);
    if (viewport != null)
      recreateViewport(true);
  }

  // writeClientCutText() is called from the clipboard dialog
  public void writeClientCutText(String str, int len) {
    if (state() != RFBSTATE_NORMAL || shuttingDown || benchmark)
      return;
    writer().writeClientCutText(str, len);
  }

  public void writeKeyEvent(int keysym, boolean down) {
    if (state() != RFBSTATE_NORMAL || shuttingDown || benchmark)
      return;
    writer().writeKeyEvent(keysym, down);
  }

  public void writeKeyEvent(KeyEvent ev) {
    int keysym = 0, keycode, key;

    if (shuttingDown || benchmark)
      return;

    boolean down = (ev.getID() == KeyEvent.KEY_PRESSED);

    keycode = ev.getKeyCode();
    key = ev.getKeyChar();

    vlog.debug((ev.isActionKey() ? "action " : "") + "key " +
               (down ? "PRESS" : "release") + " code " + keycode + " ASCII " +
                key);

    if (!ev.isActionKey()) {
      switch (keycode) {
      case KeyEvent.VK_BACK_SPACE: keysym = Keysyms.BackSpace; break;
      case KeyEvent.VK_TAB:        keysym = Keysyms.Tab; break;
      case KeyEvent.VK_ENTER:      keysym = Keysyms.Return; break;
      case KeyEvent.VK_ESCAPE:     keysym = Keysyms.Escape; break;
      case KeyEvent.VK_NUMPAD0:    keysym = Keysyms.KP_0; break;
      case KeyEvent.VK_NUMPAD1:    keysym = Keysyms.KP_1; break;
      case KeyEvent.VK_NUMPAD2:    keysym = Keysyms.KP_2; break;
      case KeyEvent.VK_NUMPAD3:    keysym = Keysyms.KP_3; break;
      case KeyEvent.VK_NUMPAD4:    keysym = Keysyms.KP_4; break;
      case KeyEvent.VK_NUMPAD5:    keysym = Keysyms.KP_5; break;
      case KeyEvent.VK_NUMPAD6:    keysym = Keysyms.KP_6; break;
      case KeyEvent.VK_NUMPAD7:    keysym = Keysyms.KP_7; break;
      case KeyEvent.VK_NUMPAD8:    keysym = Keysyms.KP_8; break;
      case KeyEvent.VK_NUMPAD9:    keysym = Keysyms.KP_9; break;
      case KeyEvent.VK_DECIMAL:    keysym = Keysyms.KP_Decimal; break;
      case KeyEvent.VK_ADD:        keysym = Keysyms.KP_Add; break;
      case KeyEvent.VK_SUBTRACT:   keysym = Keysyms.KP_Subtract; break;
      case KeyEvent.VK_MULTIPLY:   keysym = Keysyms.KP_Multiply; break;
      case KeyEvent.VK_DIVIDE:     keysym = Keysyms.KP_Divide; break;
      case KeyEvent.VK_DELETE:     keysym = Keysyms.Delete; break;
      case KeyEvent.VK_CLEAR:      keysym = Keysyms.Clear; break;
      case KeyEvent.VK_CONTROL:
        if (down)
          modifiers |= Event.CTRL_MASK;
        else
          modifiers &= ~Event.CTRL_MASK;
        keysym = Keysyms.Control_L; break;
      case KeyEvent.VK_ALT:
        if (down)
          modifiers |= Event.ALT_MASK;
        else
          modifiers &= ~Event.ALT_MASK;
        keysym = Keysyms.Alt_L; break;
      case KeyEvent.VK_SHIFT:
        if (down)
          modifiers |= Event.SHIFT_MASK;
        else
          modifiers &= ~Event.SHIFT_MASK;
        keysym = Keysyms.Shift_L; break;
      case KeyEvent.VK_META:
        if (down)
          modifiers |= Event.META_MASK;
        else
          modifiers &= ~Event.META_MASK;
        keysym = Keysyms.Meta_L; break;
      default:
        if (ev.isControlDown()) {
          // For CTRL-<letter>, CTRL is sent separately, so just send <letter>.
          if ((key >= 1 && key <= 26 && !ev.isShiftDown()) ||
              // CTRL-{, CTRL-|, CTRL-} also map to ASCII 96-127
              (key >= 27 && key <= 29 && ev.isShiftDown()))
            key += 96;
          // For CTRL-SHIFT-<letter>, send capital <letter> to emulate behavior
          // of Linux.  For CTRL-@, send @.  For CTRL-_, send _.  For CTRL-^,
          // send ^.
          else if (key < 32)
            key += 64;
          // Windows and Mac sometimes return CHAR_UNDEFINED with CTRL-SHIFT
          // combinations, so best we can do is send the key code if it is
          // a valid ASCII symbol.
          else if (key == KeyEvent.CHAR_UNDEFINED && keycode >= 0 &&
                   keycode <= 127)
            key = keycode;
        }
        keysym = UnicodeToKeysym.translate(key);
        if (keysym == -1)
          return;
      }
    } else {
      // KEY_ACTION
      switch (keycode) {
      case KeyEvent.VK_HOME:         keysym = Keysyms.Home; break;
      case KeyEvent.VK_END:          keysym = Keysyms.End; break;
      case KeyEvent.VK_PAGE_UP:      keysym = Keysyms.Page_Up; break;
      case KeyEvent.VK_PAGE_DOWN:    keysym = Keysyms.Page_Down; break;
      case KeyEvent.VK_UP:           keysym = Keysyms.Up; break;
      case KeyEvent.VK_DOWN:         keysym = Keysyms.Down; break;
      case KeyEvent.VK_LEFT:         keysym = Keysyms.Left; break;
      case KeyEvent.VK_RIGHT:        keysym = Keysyms.Right; break;
      case KeyEvent.VK_F1:           keysym = Keysyms.F1; break;
      case KeyEvent.VK_F2:           keysym = Keysyms.F2; break;
      case KeyEvent.VK_F3:           keysym = Keysyms.F3; break;
      case KeyEvent.VK_F4:           keysym = Keysyms.F4; break;
      case KeyEvent.VK_F5:           keysym = Keysyms.F5; break;
      case KeyEvent.VK_F6:           keysym = Keysyms.F6; break;
      case KeyEvent.VK_F7:           keysym = Keysyms.F7; break;
      case KeyEvent.VK_F8:           keysym = Keysyms.F8; break;
      case KeyEvent.VK_F9:           keysym = Keysyms.F9; break;
      case KeyEvent.VK_F10:          keysym = Keysyms.F10; break;
      case KeyEvent.VK_F11:          keysym = Keysyms.F11; break;
      case KeyEvent.VK_F12:          keysym = Keysyms.F12; break;
      case KeyEvent.VK_F13:          keysym = Keysyms.F13; break;
      case KeyEvent.VK_PRINTSCREEN:  keysym = Keysyms.Print; break;
      case KeyEvent.VK_PAUSE:
        if (ev.isControlDown())
          keysym = Keysyms.Break;
        else
          keysym = Keysyms.Pause;
        break;
      case KeyEvent.VK_INSERT:       keysym = Keysyms.Insert; break;
      case KeyEvent.VK_KP_DOWN:      keysym = Keysyms.KP_Down; break;
      case KeyEvent.VK_KP_LEFT:      keysym = Keysyms.KP_Left; break;
      case KeyEvent.VK_KP_RIGHT:     keysym = Keysyms.KP_Right; break;
      case KeyEvent.VK_KP_UP:        keysym = Keysyms.KP_Up; break;
      case KeyEvent.VK_NUM_LOCK:     keysym = Keysyms.Num_Lock; break;
      case KeyEvent.VK_WINDOWS:      keysym = Keysyms.Super_L; break;
      case KeyEvent.VK_CONTEXT_MENU: keysym = Keysyms.Menu; break;
      case KeyEvent.VK_SCROLL_LOCK:  keysym = Keysyms.Scroll_Lock; break;
      case KeyEvent.VK_CAPS_LOCK:    keysym = Keysyms.Caps_Lock; break;
      case KeyEvent.VK_BEGIN:        keysym = Keysyms.Begin; break;
      default: return;
      }
    }

    writeKeyEvent(keysym, down);
  }


  public void writePointerEvent(MouseEvent ev) {
    if (state() != RFBSTATE_NORMAL || shuttingDown || benchmark)
      return;

    switch (ev.getID()) {
    case MouseEvent.MOUSE_PRESSED:
      buttonMask = 1;
      if ((ev.getModifiers() & KeyEvent.ALT_MASK) != 0) buttonMask = 2;
      if ((ev.getModifiers() & KeyEvent.META_MASK) != 0) buttonMask = 4;
      break;
    case MouseEvent.MOUSE_RELEASED:
      buttonMask = 0;
      break;
    }

    if (cp.width != desktop.scaledWidth ||
        cp.height != desktop.scaledHeight) {
      int sx = (desktop.scaleWidthRatio == 1.00) ?
        ev.getX() : (int)Math.floor(ev.getX() / desktop.scaleWidthRatio);
      int sy = (desktop.scaleHeightRatio == 1.00) ?
        ev.getY() : (int)Math.floor(ev.getY() / desktop.scaleHeightRatio);
      ev.translatePoint(sx - ev.getX(), sy - ev.getY());
    }
    if (viewport != null && (viewport.dx > 0 || viewport.dy > 0)) {
      int dx = (int)Math.floor(viewport.dx / desktop.scaleWidthRatio);
      int dy = (int)Math.floor(viewport.dy / desktop.scaleHeightRatio);
      ev.translatePoint(-dx, -dy);
    }

    writer().writePointerEvent(new Point(ev.getX(), ev.getY()), buttonMask);
  }


  public void writeWheelEvent(MouseWheelEvent ev) {
    if (state() != RFBSTATE_NORMAL || shuttingDown || benchmark)
      return;
    int x, y;
    int clicks = ev.getWheelRotation();
    if (clicks < 0) {
      buttonMask = 8;
    } else {
      buttonMask = 16;
    }
    if (viewport != null && (viewport.dx > 0 || viewport.dy > 0)) {
      int dx = (int)Math.floor(viewport.dx / desktop.scaleWidthRatio);
      int dy = (int)Math.floor(viewport.dy / desktop.scaleHeightRatio);
      ev.translatePoint(-dx, -dy);
    }
    for (int i = 0; i < Math.abs(clicks); i++) {
      x = ev.getX();
      y = ev.getY();
      writer().writePointerEvent(new Point(x, y), buttonMask);
      buttonMask = 0;
      writer().writePointerEvent(new Point(x, y), buttonMask);
    }

  }


  synchronized void releaseModifiers() {
    if ((modifiers & Event.SHIFT_MASK) != 0)
      writeKeyEvent(Keysyms.Shift_L, false);
    if ((modifiers & Event.CTRL_MASK) != 0)
      writeKeyEvent(Keysyms.Control_L, false);
    if ((modifiers & Event.ALT_MASK) != 0)
      writeKeyEvent(Keysyms.Alt_L, false);
    if ((modifiers & Event.META_MASK) != 0)
      writeKeyEvent(Keysyms.Meta_L, false);
    modifiers = 0;
  }


  ////////////////////////////////////////////////////////////////////
  // The following methods are called from both RFB and GUI threads

  // checkEncodings() sends a setEncodings message if one is needed.
  private void checkEncodings() {
    if (encodingChange && (writer() != null)) {
      vlog.info("Requesting " + Encodings.encodingName(currentEncoding) +
        " encoding");
      writer().writeSetEncodings(currentEncoding, lastServerEncoding, opts);
      encodingChange = false;
      if (viewport != null)
        viewport.updateTitle();
    }
  }

  // the following never change so need no synchronization:


  // viewer object is only ever accessed by the GUI thread so needs no
  // synchronization (except for one test in DesktopWindow - see comment
  // there).
  VncViewer viewer;

  // access to desktop by different threads is specified in DesktopWindow

  // the following need no synchronization:

  ClassLoader cl = this.getClass().getClassLoader();
  ImageIcon logo = new ImageIcon(cl.getResource("com/turbovnc/vncviewer/turbovnc.png"));
  public static UserPasswdGetter upg;
  public UserMsgBox msg;

  // shuttingDown is set by the GUI thread and only ever tested by the RFB
  // thread after the window has been destroyed.
  boolean shuttingDown = false;

  // JW: Accessor method for Launcher.
  public boolean shuttingDown()
  {
      return shuttingDown;
  }

  // All menu, options, about and info stuff is done in the GUI thread (apart
  // from when constructed).
  F8Menu menu;
  OptionsDialog options;

  // JW: Accessor method for Launcher.
  public OptionsDialog getOptionsDialog()
  {
      return options;
  }

  // clipboard sync issues?
  ClipboardDialog clipboardDialog;

  // JW made opts public so it can be accessed from outside of the com.turbovnc.* packages.
  public Options opts;

  // the following are only ever accessed by the GUI thread:
  int buttonMask;

  private Socket sock;

  protected DesktopWindow desktop;

  // FIXME: should be private
  public PixelFormat serverPF;
  private PixelFormat fullColourPF;

  private boolean pendingPFChange;
  private PixelFormat pendingPF;

  public int currentEncoding, lastServerEncoding;

  private boolean formatChange;
  private boolean encodingChange;

  private boolean firstUpdate;
  private boolean pendingUpdate;
  private boolean continuousUpdates;

  private boolean forceNonincremental;

  private boolean supportsSyncFence;

  int modifiers;
  Viewport viewport;
  boolean showToolbar;

  public double tDecode, tBlit;
  double tDecodeStart, tReadOld;
  boolean benchmark;

  static LogWriter vlog = new LogWriter("CConn");
}
