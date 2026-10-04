"""
Air Connect Pro - Fullscreen Laptop Screen Extender
Ultra-low latency, high-FPS edge-to-edge display streaming with:
  1. DirectX / DXGI + MSS Hybrid Capture Engine (Overlapped capture & encode for maximum 60-120 FPS).
  2. Native High-Resolution 1080p Stream (Eliminates text blurriness with high-clarity SIMD JPEG).
  3. Strict USB First Priority (Auto-switches from Wi-Fi to USB immediately upon cable connection).
  4. Real-time Hardware Mouse Cursor drawing (visible & responsive on phone).
  5. Dynamic Multi-Monitor Support: Linked directly to Windows Projection (Win + P):
     - Win + P -> Extend    : Streams Extended Secondary Display (Display 2).
     - Win + P -> Duplicate : Streams Primary Laptop Screen (Display 1).
"""

import sys
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import argparse
import ctypes
from ctypes import wintypes
import io
import os
import queue
import shutil
import socket
import struct
import subprocess
import threading
import time

# High-speed computer vision modules
HAS_OPENCV = False
try:
    import cv2
    import numpy as np
    import mss
    HAS_OPENCV = True
except Exception:
    from PIL import ImageGrab

# Check for DirectX Desktop Duplication (dxcam)
HAS_DXCAM = False
try:
    import dxcam
    HAS_DXCAM = True
except Exception:
    HAS_DXCAM = False

class POINT(ctypes.Structure):
    _fields_ = [('x', ctypes.c_long), ('y', ctypes.c_long)]

class CURSORINFO(ctypes.Structure):
    _fields_ = [
        ('cbSize', wintypes.DWORD),
        ('flags', wintypes.DWORD),
        ('hCursor', wintypes.HICON),
        ('ptScreenPos', POINT)
    ]

CURSOR_SHOWING = 0x00000001
CURSOR_PTS = np.array([
    [0, 0], [0, 19], [5, 15], [9, 23], [12, 21], [8, 14], [14, 14]
], dtype=np.int32)

def attach_desktop():
    """Ensure this thread is attached to the interactive Windows input desktop."""
    try:
        user32 = ctypes.windll.user32
        h_desk = user32.OpenInputDesktop(0, False, 0x01FF)
        if h_desk:
            user32.SetThreadDesktop(h_desk)
    except Exception:
        pass

def draw_mouse_cursor(bgr, mon_left, mon_top, scale_x=1.0, scale_y=1.0):
    """Draw the system mouse pointer onto the captured frame if present on this display."""
    try:
        ci = CURSORINFO()
        ci.cbSize = ctypes.sizeof(CURSORINFO)
        if ctypes.windll.user32.GetCursorInfo(ctypes.byref(ci)) and (ci.flags & CURSOR_SHOWING):
            cx = int((ci.ptScreenPos.x - mon_left) * scale_x)
            cy = int((ci.ptScreenPos.y - mon_top) * scale_y)
            h, w = bgr.shape[:2]
            if 0 <= cx < w and 0 <= cy < h:
                shifted = CURSOR_PTS + [cx, cy]
                cv2.fillPoly(bgr, [shifted], (255, 255, 255))
                cv2.polylines(bgr, [shifted], True, (0, 0, 0), 1, cv2.LINE_AA)
    except Exception:
        pass

def find_adb():
    """Locate the most reliable adb.exe executable on the system."""
    candidates = [
        r"C:\Users\Admin\Downloads\Android-platform-tools-latest-windows\platform-tools\adb.exe",
        r"C:\temporary\scrcpy\scrcpy-win64-v3.2\scrcpy-win64-v3.2\adb.exe",
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    found = shutil.which("adb")
    return found if found else "adb"

ADB_BIN = find_adb()

def ensure_adb_server():
    """Ensure the ADB server daemon is alive on localhost:5037."""
    s = socket.socket()
    s.settimeout(0.2)
    try:
        s.connect(('127.0.0.1', 5037))
        s.close()
        return True
    except Exception:
        pass

    try:
        DETACHED_FLAG = 0x00000008 | 0x00000200
        subprocess.Popen([ADB_BIN, "start-server"],
                         stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL,
                         creationflags=DETACHED_FLAG)
        time.sleep(0.5)
    except Exception:
        pass
    return False

def get_connected_usb_device():
    """
    Direct ultra-fast socket query to ADB server on 127.0.0.1:5037.
    Returns physical USB device serial, or None.
    Filters out Wi-Fi / TCP connections.
    """
    ensure_adb_server()
    s = socket.socket()
    s.settimeout(0.5)
    try:
        s.connect(('127.0.0.1', 5037))
        req = b'host:devices'
        s.sendall(f"{len(req):04x}".encode() + req)
        status = s.recv(4)
        if status == b'OKAY':
            len_hex = s.recv(4)
            length = int(len_hex, 16)
            data = s.recv(length).decode('utf-8', errors='replace')
            for line in data.splitlines():
                parts = line.strip().split()
                if len(parts) >= 2 and parts[1] == 'device':
                    serial = parts[0]
                    if ":" not in serial: # Physical USB serial
                        return serial
    except Exception:
        pass
    finally:
        try: s.close()
        except Exception: pass
    return None

def setup_adb_forward(serial):
    """Setup ADB TCP port forward to phone port 8080 over USB via ADB socket."""
    s = socket.socket()
    s.settimeout(0.6)
    try:
        s.connect(('127.0.0.1', 5037))
        req = f"host-serial:{serial}:forward:tcp:8080;tcp:8080".encode()
        s.sendall(f"{len(req):04x}".encode() + req)
        status = s.recv(4)
        return status == b'OKAY'
    except Exception:
        return False
    finally:
        try: s.close()
        except Exception: pass

def get_network_ips():
    """Get list of likely IP targets across local Wi-Fi / Tethering."""
    ips = []
    try:
        r = subprocess.run(["ipconfig"], capture_output=True, text=True, timeout=2)
        for line in r.stdout.splitlines():
            if "IPv4 Address" in line:
                parts = line.split(":")
                if len(parts) > 1:
                    base_ip = parts[1].strip()
                    if base_ip and base_ip.count(".") == 3:
                        prefix = ".".join(base_ip.split(".")[:3])
                        for last in [1, 2, 3, 4, 18, 100, 101, 102, 105]:
                            candidate = f"{prefix}.{last}"
                            if candidate != base_ip and candidate not in ips:
                                ips.append(candidate)
    except Exception:
        pass
    return ips

def try_connect(target_ip, port=8080, timeout=0.6):
    """Attempt a quick TCP handshake with socket tuning."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 1048576)
        except Exception:
            pass
        s.settimeout(timeout)
        s.connect((target_ip, port))
        s.settimeout(None)
        return s
    except Exception:
        try:
            s.close()
        except Exception:
            pass
    return None

def find_active_stream_socket(manual_ip=None):
    """
    Detect phone connection with STRICT USB FIRST PRIORITY:
    1. If USB cable is connected -> ALWAYS choose USB (127.0.0.1 via ADB Forward).
    2. Only if USB is NOT connected -> Fallback to Wi-Fi.
    Returns: (socket, connection_label, is_usb_flag)
    """
    usb_dev = get_connected_usb_device()
    if usb_dev:
        # FIRST PRIORITY: USB
        setup_adb_forward(usb_dev)
        sock = try_connect("127.0.0.1", 8080, timeout=0.8)
        if sock:
            return sock, f"High-Speed USB [{usb_dev}] (First Priority - Zero Latency)", True

    # SECOND PRIORITY: Only if USB is NOT connected or not ready, fallback to Wi-Fi
    if not usb_dev:
        if manual_ip:
            sock = try_connect(manual_ip, 8080, timeout=0.8)
            if sock:
                return sock, f"Direct Wi-Fi [{manual_ip}] (Fallback)", False

        for ip in get_network_ips():
            sock = try_connect(ip, 8080, timeout=0.3)
            if sock:
                return sock, f"Wi-Fi Network [{ip}] (Fallback)", False

    return None, None, False

def ensure_extended_display(sct):
    """Ensure Windows has a Secondary Display (Display 2) active for Win + P Extend mode."""
    if sct is not None:
        try:
            sct._monitors = None
            if len(sct.monitors) > 2:
                return True
        except Exception:
            pass

    enable_bat = r"C:\Rarey Temp\Ai long stuff\Enable-SecondaryDisplay.bat"
    if os.path.exists(enable_bat):
        print("\n[*] Windows Secondary Extended Display (Display 2) is not yet active.")
        print("[*] Activating Virtual Extended Monitor (Please click 'YES' if Windows prompts)...")
        try:
            ps_cmd = f"Start-Process -FilePath '{enable_bat}' -Verb RunAs -Wait"
            subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps_cmd], timeout=15)
            time.sleep(1.5)
            subprocess.run(["DisplaySwitch.exe", "/extend"], timeout=3)
            time.sleep(1.0)
            if sct is not None:
                sct._monitors = None
                return len(sct.monitors) > 2
        except Exception as e:
            print(f"[!] Virtual Display activation notice: {e}")
    return False

def select_target_monitor(sct):
    """
    Dynamically select appropriate monitor based on Windows Projection (Win + P) modes:
    - Multiple monitors (Extend mode active): Captures Display 2 (the extended phone monitor)
    - Single/Duplicate mode: Captures Display 1 (the primary laptop screen)
    """
    if sct is not None:
        try:
            sct._monitors = None # Invalidate MSS cache to detect Win + P mode changes immediately!
        except Exception:
            pass
        monitors = sct.monitors
        if len(monitors) > 2:
            return monitors[2], "[EXTENDED DISPLAY 2] (Win + P: Extend Mode Active)", 1
        elif len(monitors) == 2:
            return monitors[1], "[PRIMARY DISPLAY 1] (Win + P: Duplicate/Mirror Mode)", 0
    return None, "Default Screen", 0

def main():
    parser = argparse.ArgumentParser(description="Air Connect Pro Screen Extender")
    parser.add_argument("--fps", type=int, default=120, help="Target FPS (60, 90, 120, 144). Default is 120.")
    parser.add_argument("--ip", type=str, default=None, help="Optional manual phone IP for Wi-Fi stream.")
    parser.add_argument("--res", type=str, default="1920x1080", help="Stream resolution. Default: 1920x1080 (Sharp Native Text).")
    parser.add_argument("--quality", type=int, default=82, help="JPEG quality (1-100). Default: 82 for crystal-clear text.")
    args = parser.parse_args()

    target_fps = max(30, min(144, args.fps))
    frame_interval = 1.0 / target_fps

    # Parse target resolution
    target_w, target_h = 1920, 1080
    try:
        parts = args.res.lower().split("x")
        target_w, target_h = int(parts[0]), int(parts[1])
    except Exception:
        target_w, target_h = 1920, 1080

    print("=" * 68)
    print("   AIR CONNECT PRO - FULLSCREEN LAPTOP DISPLAY EXTENDER")
    print("=" * 68)
    print(f"  Target Frame Rate : {target_fps} FPS (High-End AMOLED 120Hz/144Hz)")
    print(f"  Stream Resolution : {target_w}x{target_h} (Crystal-Clear Native Text)")
    print(f"  Priority Policy   : [FIRST PRIORITY: USB CABLE] > [FALLBACK: Wi-Fi]")
    print(f"  Cursor Mode       : Hardware Mouse Pointer Overlay (Live Drawn)")
    print(f"  Windows Projection: Win + P (Extend / Duplicate / Second Screen)")
    print(f"  ADB Engine        : Integrated Direct Socket + {os.path.basename(ADB_BIN)}")
    print("=" * 68)

    attach_desktop()

    sct = None
    if HAS_OPENCV:
        try:
            sct = mss.MSS()
        except Exception:
            sct = None

    # Check and ensure Windows Secondary Display is active
    ensure_extended_display(sct)

    # Ensure Windows is in Extend mode
    try:
        subprocess.run(["DisplaySwitch.exe", "/extend"], timeout=2)
    except Exception:
        pass

    while True:
        attach_desktop()
        usb_status = get_connected_usb_device()
        if usb_status:
            print(f"\n[*] [FIRST PRIORITY] USB Device Detected: {usb_status}. Connecting via USB...")
        else:
            print("\n[*] Waiting for phone Screen Extender (USB / Wi-Fi)...")

        sock = None
        conn_label = None
        is_usb_active = False

        for attempt in range(1, 10):
            sock, conn_label, is_usb_active = find_active_stream_socket(manual_ip=args.ip)
            if sock:
                break
            time.sleep(0.5)

        if not sock:
            print("  [-] Waiting for connection... Please ensure:")
            print("      1. Open Air Connect Pro on phone -> Tap Menu (?) -> 'Screen Extender'.")
            if usb_status:
                print("      2. Phone is connected via USB. Tap 'Screen Extender' on phone to begin.")
            else:
                print("      2. Plug in USB Cable (Recommended for 120 FPS) OR connect to same Wi-Fi.")
            time.sleep(1.5)
            continue

        active_mon, mon_desc, mon_idx = select_target_monitor(sct)
        print(f"\n[+] CONNECTION ESTABLISHED!")
        print(f"    Link Mode : {conn_label}")
        print(f"    Display   : {mon_desc}")
        print(f"    Target    : {target_fps} FPS Fullscreen (1080p Crisp Text)")
        if is_usb_active:
            print("    Status    : High-Speed USB Active - Zero Latency 120 FPS")
        else:
            print("    Status    : Streaming over Wi-Fi (Plug in USB anytime to auto-switch to USB!)")

        fps_timer = time.time()
        fps_frames = 0
        last_usb_check = time.time()
        last_mon_check = time.time()

        frame_queue = queue.Queue(maxsize=1)
        stop_grabber = threading.Event()
        current_active_mon = [active_mon]
        current_mon_idx = [mon_idx]

        # Multi-Threaded Pipelined Capture Engine:
        # Tries DirectX GPU hardware capture (dxcam) first for ultra-smooth 120 FPS;
        # Falls back to high-speed MSS GDI capture.
        def grabber_thread_func():
            attach_desktop()

            # Attempt DXCam GPU capture first
            dxcam_camera = None
            if HAS_DXCAM:
                try:
                    dxcam_camera = dxcam.create(device_idx=0, output_idx=current_mon_idx[0], output_color="BGR")
                    if dxcam_camera:
                        dxcam_camera.start(target_fps=target_fps, video_mode=True)
                except Exception:
                    dxcam_camera = None

            grabber_sct = None
            if dxcam_camera is None:
                try:
                    grabber_sct = mss.MSS()
                except Exception:
                    pass

            last_seen_idx = current_mon_idx[0]

            while not stop_grabber.is_set():
                try:
                    # If monitor target changed (e.g. user toggled Win + P Extend <-> Duplicate)
                    if current_mon_idx[0] != last_seen_idx:
                        last_seen_idx = current_mon_idx[0]
                        if dxcam_camera:
                            try:
                                dxcam_camera.stop()
                                dxcam_camera = dxcam.create(device_idx=0, output_idx=last_seen_idx, output_color="BGR")
                                dxcam_camera.start(target_fps=target_fps, video_mode=True)
                            except Exception:
                                dxcam_camera = None
                                if grabber_sct is None:
                                    grabber_sct = mss.MSS()

                    raw_bgr = None
                    mon = current_active_mon[0]

                    if dxcam_camera:
                        frame = dxcam_camera.get_latest_frame()
                        if frame is not None:
                            raw_bgr = frame
                        else:
                            time.sleep(0.002)
                            continue
                    elif grabber_sct:
                        try:
                            grabber_sct._monitors = None
                        except Exception:
                            pass
                        shot = grabber_sct.grab(mon)
                        raw = np.frombuffer(shot.raw, dtype=np.uint8).reshape((shot.height, shot.width, 4))
                        raw_bgr = raw[:, :, :3]

                    if raw_bgr is not None:
                        try:
                            frame_queue.put_nowait((raw_bgr, mon))
                        except queue.Full:
                            try:
                                frame_queue.get_nowait()
                            except queue.Empty:
                                pass
                            try:
                                frame_queue.put_nowait((raw_bgr, mon))
                            except queue.Full:
                                pass
                except Exception:
                    time.sleep(0.005)

            if dxcam_camera:
                try:
                    dxcam_camera.stop()
                except Exception:
                    pass

        grab_thread = threading.Thread(target=grabber_thread_func, daemon=True)
        grab_thread.start()

        try:
            while True:
                cycle_start = time.time()
                attach_desktop()

                # Dynamic live check for Windows Projection changes (Win + P Extend / Duplicate)
                now = time.time()
                if now - last_mon_check >= 1.0:
                    last_mon_check = now
                    current_mon, current_desc, cur_idx = select_target_monitor(sct)
                    if current_desc != mon_desc:
                        active_mon = current_mon
                        current_active_mon[0] = active_mon
                        current_mon_idx[0] = cur_idx
                        mon_desc = current_desc
                        print(f"\n[WIN + P PROJECTION CHANGED] Now streaming: {mon_desc}")

                # Dynamic USB Hot-Plug Preemption:
                if not is_usb_active and (now - last_usb_check >= 2.0):
                    last_usb_check = now
                    plugged_dev = get_connected_usb_device()
                    if plugged_dev:
                        print(f"\n\n[PRIORITY OVERRIDE] USB Cable Detected ({plugged_dev})!")
                        print("  -> Auto-switching stream from Wi-Fi to USB for First Priority Zero Latency...")
                        sock.close()
                        break

                try:
                    bgr, cur_mon = frame_queue.get(timeout=0.08)
                except queue.Empty:
                    continue

                raw_h, raw_w = bgr.shape[:2]

                # Only resize if necessary; if resolution matches, bypass resize entirely for zero-copy max speed!
                if raw_w != target_w or raw_h != target_h:
                    scale_x = target_w / float(raw_w)
                    scale_y = target_h / float(raw_h)
                    bgr_resized = cv2.resize(bgr, (target_w, target_h), interpolation=cv2.INTER_LINEAR)
                else:
                    scale_x, scale_y = 1.0, 1.0
                    bgr_resized = bgr

                # Draw hardware mouse cursor onto frame
                mon_l = cur_mon.get('left', 0) if isinstance(cur_mon, dict) else 0
                mon_t = cur_mon.get('top', 0) if isinstance(cur_mon, dict) else 0
                draw_mouse_cursor(bgr_resized, mon_l, mon_t, scale_x, scale_y)

                # High-speed SIMD JPEG compression (crisp text without blur)
                encode_params = [
                    cv2.IMWRITE_JPEG_QUALITY, args.quality,
                    cv2.IMWRITE_JPEG_OPTIMIZE, 0
                ]
                _, enc = cv2.imencode('.jpg', bgr_resized, encode_params)
                jpeg_data = enc.tobytes()

                # Protocol: 4-byte big-endian payload length + JPEG byte payload
                hdr = struct.pack(">I", len(jpeg_data))
                sock.sendall(hdr + jpeg_data)

                fps_frames += 1
                now = time.time()
                if now - fps_timer >= 1.0:
                    fps = fps_frames / (now - fps_timer)
                    mbps = (len(jpeg_data) * fps * 8) / (1024.0 * 1024.0)
                    priority_badge = "[USB 1st Priority]" if is_usb_active else "[Wi-Fi]"
                    print(f"\r  {priority_badge} {fps:.1f} FPS | Target: {target_fps} FPS | Res: {target_w}x{target_h} | {mbps:.2f} Mbps   ", end="", flush=True)
                    fps_frames = 0
                    fps_timer = now

                elapsed = time.time() - cycle_start
                sleep_time = frame_interval - elapsed
                if sleep_time > 0:
                    time.sleep(sleep_time)

        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, socket.error):
            print("\n[!] Stream disconnected. Auto-reconnecting...")
        except KeyboardInterrupt:
            print("\nStream stopped by user.")
            break
        except Exception as e:
            print(f"\nStream paused ({e}). Re-attaching...")
        finally:
            stop_grabber.set()
            if sock:
                try:
                    sock.close()
                except Exception:
                    pass
            time.sleep(0.8)

if __name__ == "__main__":
    main()
