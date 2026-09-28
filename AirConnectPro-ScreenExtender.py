"""
Air Connect Pro - Laptop Screen Extender (Spacedesk Mode)
Ultra-low latency, high-FPS edge-to-edge display streaming over USB or Same Wi-Fi Network.
Supports 60, 90, 120, 144 FPS with hardware-accelerated SIMD OpenCV encoding.
"""

import sys
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import argparse
import ctypes
import io
import os
import socket
import struct
import subprocess
import time

# Try importing high-speed modules
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

def setup_adb_ports():
    """Configure USB port forwarding via ADB."""
    try:
        subprocess.run(["adb", "forward", "tcp:8080", "tcp:8080"], capture_output=True, timeout=1.5)
        subprocess.run(["adb", "reverse", "tcp:8080", "tcp:8080"], capture_output=True, timeout=1.5)
    except Exception:
        pass

def get_network_ips():
    """Get list of likely IP targets across USB and local Wi-Fi."""
    ips = ["127.0.0.1", "10.122.172.18"]
    try:
        r = subprocess.run(["ipconfig"], capture_output=True, text=True, timeout=2)
        for line in r.stdout.splitlines():
            if "Default Gateway" in line or "IPv4 Address" in line:
                parts = line.split(":")
                if len(parts) > 1:
                    ip = parts[1].strip()
                    if ip and ip.count(".") == 3 and not ip.startswith("127.") and ip not in ips:
                        ips.append(ip)
    except Exception:
        pass
    return ips

def try_connect(target_ip, port=8080, timeout=0.6):
    """Attempt a quick TCP handshake."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
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
    """Detect phone connection across USB ADB and Same Network (Wi-Fi)."""
    setup_adb_ports()

    # Priority 1: High-Speed USB ADB
    sock = try_connect("127.0.0.1", 8080, timeout=0.5)
    if sock:
        return sock, "High-Speed USB (Zero Lag)"

    # Priority 2: Manual IP (if specified)
    if manual_ip:
        sock = try_connect(manual_ip, 8080, timeout=1.0)
        if sock:
            return sock, f"Direct Wi-Fi ({manual_ip})"

    # Priority 3: Auto-detect local Wi-Fi / Tethering IPs
    for ip in get_network_ips():
        if ip == "127.0.0.1":
            continue
        sock = try_connect(ip, 8080, timeout=0.4)
        if sock:
            mode = "USB Tethering" if ip.startswith("10.122.") else f"Wi-Fi Network ({ip})"
            return sock, mode

    return None, None

def main():
    parser = argparse.ArgumentParser(description="Air Connect Pro Screen Extender")
    parser.add_argument("--fps", type=int, default=120, help="Target FPS (60, 90, 120, 144). Default is 120.")
    parser.add_argument("--ip", type=str, default=None, help="Optional manual phone IP for Wi-Fi stream.")
    args = parser.parse_args()

    target_fps = max(30, min(144, args.fps))
    frame_interval = 1.0 / target_fps

    print("=" * 65)
    print("   AIR CONNECT PRO - FULLSCREEN LAPTOP DISPLAY EXTENDER")
    print("=" * 65)
    print(f"  Target Frame Rate : {target_fps} FPS (High-End AMOLED 120Hz/144Hz)")
    print(f"  Capture Engine    : {'OpenCV + MSS (Ultra Fast)' if HAS_OPENCV else 'Pillow ImageGrab'}")
    print("  Display Mode      : Edge-to-Edge Fullscreen (Zero UI Borders)")
    print("  Connection Modes  : 1. USB Cable (Primary) | 2. Same Wi-Fi Network")
    print("=" * 65)

    attach_desktop()

    sct = None
    if HAS_OPENCV:
        try:
            sct = mss.mss()
        except Exception:
            sct = None

    while True:
        attach_desktop()
        print("\n[*] Waiting for phone Screen Extender (Checking USB & Wi-Fi)...")

        sock = None
        conn_label = None

        for attempt in range(1, 15):
            sock, conn_label = find_active_stream_socket(manual_ip=args.ip)
            if sock:
                break
            time.sleep(0.6)

        if not sock:
            print("  [-] Phone not detected yet. Please ensure:")
            print("      1. Phone is connected via USB cable OR same Wi-Fi network.")
            print("      2. Open Air Connect Pro on phone -> Tap Menu (☰) -> 'Screen Extender'.")
            time.sleep(2.0)
            continue

        print(f"\n[+] CONNECTED VIA: {conn_label}!")
        print(f"[+] Streaming laptop screen live at {target_fps} FPS...")

        fps_timer = time.time()
        fps_frames = 0

        try:
            while True:
                cycle_start = time.time()
                attach_desktop()

                jpeg_data = None
                img_w, img_h = 1280, 720

                if HAS_OPENCV and sct is not None:
                    mon = sct.monitors[1]
                    shot = sct.grab(mon)
                    raw_w, raw_h = shot.width, shot.height
                    frame = np.frombuffer(shot.raw, dtype=np.uint8).reshape((raw_h, raw_w, 4))
                    if raw_w > 1280:
                        frame = cv2.resize(frame, (1280, int(1280 * raw_h / raw_w)), interpolation=cv2.INTER_NEAREST)
                    img_w, img_h = frame.shape[1], frame.shape[0]
                    _, enc = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 68])
                    jpeg_data = enc.tobytes()
                else:
                    from PIL import ImageGrab
                    img = ImageGrab.grab()
                    if img.width > 1280:
                        img = img.resize((1280, int(1280 * img.height / img.width)))
                    img_w, img_h = img.size
                    buf = io.BytesIO()
                    img.save(buf, format="JPEG", quality=68)
                    jpeg_data = buf.getvalue()

                # Send 4-byte big-endian header + payload
                hdr = struct.pack(">I", len(jpeg_data))
                sock.sendall(hdr + jpeg_data)

                fps_frames += 1
                now = time.time()
                if now - fps_timer >= 1.0:
                    fps = fps_frames / (now - fps_timer)
                    mbps = (len(jpeg_data) * fps * 8) / (1024.0 * 1024.0)
                    print(f"\r  [STREAMING] {fps:.1f} FPS | Target: {target_fps} FPS | Res: {img_w}x{img_h} | {mbps:.2f} Mbps", end="", flush=True)
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
            time.sleep(1.0)

if __name__ == "__main__":
    main()
