"""
Air Connect Pro - Fullscreen Laptop Screen Extender
Ultra-low latency, high-FPS edge-to-edge display streaming with Strict USB First Priority.
If both USB and Wi-Fi are connected, USB is ALWAYS selected first.
Supports dynamic USB hot-plug preemption (auto-switches from Wi-Fi to USB immediately upon cable connection).
"""

import sys
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import argparse
import ctypes
import io
import os
import shutil
import socket
import struct
import subprocess
import time

# Try importing high-speed computer vision modules
HAS_OPENCV = False
try:
    import cv2
    import numpy as np
    import mss
    HAS_OPENCV = True
except Exception:
    from PIL import ImageGrab

def attach_desktop():
    """Ensure this thread is attached to the interactive Windows input desktop."""
    try:
        user32 = ctypes.windll.user32
        h_desk = user32.OpenInputDesktop(0, False, 0x01FF)
        if h_desk:
            user32.SetThreadDesktop(h_desk)
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
        # Start detached daemon without blocking
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
    Filters out Wi-Fi / TCP connections (which contain colons).
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

def main():
    parser = argparse.ArgumentParser(description="Air Connect Pro Screen Extender")
    parser.add_argument("--fps", type=int, default=120, help="Target FPS (60, 90, 120, 144). Default is 120.")
    parser.add_argument("--ip", type=str, default=None, help="Optional manual phone IP for Wi-Fi stream.")
    parser.add_argument("--scale", type=float, default=1.0, help="Resolution scale (e.g. 1.0 for native 1080p, 0.85 for 900p).")
    args = parser.parse_args()

    target_fps = max(30, min(144, args.fps))
    frame_interval = 1.0 / target_fps

    print("=" * 68)
    print("   AIR CONNECT PRO - FULLSCREEN LAPTOP DISPLAY EXTENDER")
    print("=" * 68)
    print(f"  Target Frame Rate : {target_fps} FPS (High-End AMOLED 120Hz/144Hz)")
    print(f"  Priority Policy   : [FIRST PRIORITY: USB CABLE] > [FALLBACK: Wi-Fi]")
    print(f"  Capture Engine    : {'OpenCV + MSS (Ultra Fast)' if HAS_OPENCV else 'Pillow ImageGrab'}")
    print("  Display Mode      : Edge-to-Edge Fullscreen (Zero UI Borders)")
    print(f"  ADB Engine        : Integrated Direct Socket + {os.path.basename(ADB_BIN)}")
    print("=" * 68)

    attach_desktop()

    sct = None
    if HAS_OPENCV:
        try:
            sct = mss.MSS()
        except Exception:
            sct = None

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
            print("      1. Open Air Connect Pro on phone -> Tap Menu (☰) -> 'Screen Extender'.")
            if usb_status:
                print("      2. Phone is connected via USB. Tap 'Screen Extender' on phone to begin.")
            else:
                print("      2. Plug in USB Cable (Recommended for 120 FPS) OR connect to same Wi-Fi.")
            time.sleep(1.5)
            continue

        print(f"\n[+] CONNECTION ESTABLISHED!")
        print(f"    Mode    : {conn_label}")
        print(f"    Target  : {target_fps} FPS Fullscreen")
        if is_usb_active:
            print("    Status  : ⚡ First Priority USB Active - Maximum 120 FPS Zero Latency")
        else:
            print("    Status  : 🟡 Streaming over Wi-Fi (Plug in USB anytime to auto-switch to USB!)")

        fps_timer = time.time()
        fps_frames = 0
        last_usb_check = time.time()

        try:
            while True:
                cycle_start = time.time()
                attach_desktop()

                # DYNAMIC USB HOT-PLUG PREEMPTION:
                # If currently on Wi-Fi, check every 2s if USB was plugged in!
                if not is_usb_active:
                    now = time.time()
                    if now - last_usb_check >= 2.0:
                        last_usb_check = now
                        plugged_dev = get_connected_usb_device()
                        if plugged_dev:
                            print(f"\n\n[⚡ PRIORITY OVERRIDE] USB Cable Detected ({plugged_dev})!")
                            print("  -> Auto-switching stream from Wi-Fi to USB for First Priority Zero Latency...")
                            sock.close()
                            break

                jpeg_data = None
                img_w, img_h = 1920, 1080

                if HAS_OPENCV and sct is not None:
                    # Select primary display (or extended display 2 if present)
                    mon = sct.monitors[2] if len(sct.monitors) > 2 else sct.monitors[1]
                    shot = sct.grab(mon)
                    raw_w, raw_h = shot.width, shot.height
                    frame = np.frombuffer(shot.raw, dtype=np.uint8).reshape((raw_h, raw_w, 4))
                    bgr = frame[:, :, :3]

                    # Scale if requested or default to crisp 1600x900 / native
                    if args.scale < 1.0:
                        target_w = int(raw_w * args.scale)
                        target_h = int(raw_h * args.scale)
                        bgr = cv2.resize(bgr, (target_w, target_h), interpolation=cv2.INTER_LINEAR)
                    
                    img_w, img_h = bgr.shape[1], bgr.shape[0]
                    # Quality 80 provides crystal clear text with small packet size
                    _, enc = cv2.imencode('.jpg', bgr, [cv2.IMWRITE_JPEG_QUALITY, 80])
                    jpeg_data = enc.tobytes()
                else:
                    from PIL import ImageGrab
                    img = ImageGrab.grab()
                    img_w, img_h = img.size
                    buf = io.BytesIO()
                    img.save(buf, format="JPEG", quality=80)
                    jpeg_data = buf.getvalue()

                # Protocol: 4-byte big-endian payload length + JPEG byte payload
                hdr = struct.pack(">I", len(jpeg_data))
                sock.sendall(hdr + jpeg_data)

                fps_frames += 1
                now = time.time()
                if now - fps_timer >= 1.0:
                    fps = fps_frames / (now - fps_timer)
                    mbps = (len(jpeg_data) * fps * 8) / (1024.0 * 1024.0)
                    priority_badge = "[USB ⚡ 1st Priority]" if is_usb_active else "[Wi-Fi 🟡]"
                    print(f"\r  {priority_badge} {fps:.1f} FPS | Target: {target_fps} FPS | Res: {img_w}x{img_h} | {mbps:.2f} Mbps   ", end="", flush=True)
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
            if sock:
                try:
                    sock.close()
                except Exception:
                    pass
            time.sleep(0.8)

if __name__ == "__main__":
    main()
