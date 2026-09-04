import asyncio
import json
import urllib.request
import urllib.parse
import sys
import uuid

import websockets

SIGNALING_URL = "wss://skiff.algosculptor.com/ws"
RELAY_URL = "https://skiff.algosculptor.com/api/relay"

async def run_test():
    print("============================================================")
    print("🚀 STARTING SKIFF CROSS-NETWORK & RELAY TEST SUITE")
    print("============================================================")

    # Device ID max length in schema is VARCHAR(36) (UUID length)
    dev_a_id = str(uuid.uuid4())
    dev_b_id = str(uuid.uuid4())

    # 1. Connect Device A (Sender) over WSS
    print("\n[Device A - 4G/Cellular Simulator] Connecting to WSS...")
    async with websockets.connect(SIGNALING_URL) as ws_a:
        # Register Device A
        await ws_a.send(json.dumps({
            "type": "REGISTER",
            "device_id": dev_a_id
        }))
        res_a = json.loads(await ws_a.recv())
        dev_code_a = res_a.get("device_code")
        print(f"✅ Device A Registered. Allocated Code: [{dev_code_a}]")

        # 2. Connect Device B (Receiver) over WSS
        print("\n[Device B - Home Wi-Fi Simulator] Connecting to WSS...")
        async with websockets.connect(SIGNALING_URL) as ws_b:
            await ws_b.send(json.dumps({
                "type": "REGISTER",
                "device_id": dev_b_id
            }))
            res_b = json.loads(await ws_b.recv())
            print(f"✅ Device B Registered. Allocated Code: [{res_b.get('device_code')}]")

            # 3. Device B initiates connection to Device A using code
            print(f"\n[Device B] Requesting pairing to Code [{dev_code_a}]...")
            await ws_b.send(json.dumps({
                "type": "REQUEST_CONNECTION",
                "target_code": dev_code_a
            }))

            # Device A receives pairing request
            pair_req_a = json.loads(await ws_a.recv())
            sender_id = pair_req_a.get("sender_device_id")
            print(f"✅ Device A received pair request from Device B (ID: {sender_id})")

            # 4. Device A accepts pairing
            print("\n[Device A] Accepting pair request...")
            await ws_a.send(json.dumps({
                "type": "ACCEPT_REQUEST",
                "sender_device_id": sender_id
            }))

            pair_accept_b = json.loads(await ws_b.recv())
            print(f"✅ Device B pairing confirmed by Device A: {pair_accept_b}")

            # 5. Simulate ICE Candidate exchange (Local IP vs Public CGNAT IP)
            print("\n[ICE Exchange] Exchanging candidate IP addresses...")
            await ws_a.send(json.dumps({
                "type": "ICE_CANDIDATE",
                "target_device_id": dev_b_id,
                "candidate": "LOCAL_IP:192.168.1.105"
            }))
            ice_event_b = json.loads(await ws_b.recv())
            print(f"✅ Device B received Device A candidate: {ice_event_b.get('candidate')}")

            # 6. Simulate File Transfer Initiation
            session_id = str(uuid.uuid4())
            file_id = str(uuid.uuid4())
            test_payload = b"Hello from Skiff P2P / Relay Cross-Network Test! " * 100
            file_size = len(test_payload)

            print(f"\n[Transfer] Device A initiating transfer for file '{file_id}' ({file_size} bytes)...")
            await ws_a.send(json.dumps({
                "type": "INITIATE_TRANSFER",
                "session_id": session_id,
                "receiver_device_id": dev_b_id,
                "files": [{
                    "file_id": file_id,
                    "file_name": "cross_net_test.dat",
                    "file_path": "/storage/cross_net_test.dat",
                    "file_size": file_size,
                    "file_hash": "sha256-mock-hash"
                }]
            }))

            # Drain acknowledgment on A and incoming transfer on B
            res_init_a = json.loads(await ws_a.recv())
            init_event_b = json.loads(await ws_b.recv())
            print(f"✅ Device A session created ({res_init_a.get('type')}), Device B notified ({init_event_b.get('type')})")

            # 7. Simulate Direct TCP connection failure -> Fallback to HTTP Relay
            print("\n[Direct TCP Failure Simulation] Direct socket blocked by CGNAT firewall.")
            print("[Relay Fallback] Device A sending ICE fallback trigger candidate...")

            await ws_a.send(json.dumps({
                "type": "ICE_CANDIDATE",
                "target_device_id": dev_b_id,
                "candidate": f"RELAY_FALLBACK:{file_id}"
            }))

            fallback_event_b = json.loads(await ws_b.recv())
            print(f"✅ Device B received Relay Fallback Trigger: {fallback_event_b.get('candidate')}")

            # Concurrently run Upload and Download tasks to stream through in-memory mpsc channel
            print("\n[Relay Stream] Streaming binary data through Cloud Relay buffer...")

            async def download_task():
                download_url = f"{RELAY_URL}/download/{file_id}"
                req = urllib.request.Request(download_url, method="GET")
                req.add_header("User-Agent", "Mozilla/5.0")
                def _do_download():
                    with urllib.request.urlopen(req) as resp:
                        return resp.status, resp.read()
                status, data = await asyncio.to_thread(_do_download)
                print(f"✅ Device B Relay Download Stream Completed (HTTP {status}, {len(data)} bytes received)")
                return data

            async def upload_task():
                upload_url = f"{RELAY_URL}/upload/{file_id}"
                req = urllib.request.Request(upload_url, data=test_payload, method="POST")
                req.add_header("User-Agent", "Mozilla/5.0")
                req.add_header("Content-Type", "application/octet-stream")
                def _do_upload():
                    with urllib.request.urlopen(req) as resp:
                        return resp.status
                status = await asyncio.to_thread(_do_upload)
                print(f"✅ Device A Relay Upload Stream Completed (HTTP {status})")

            download_fut = asyncio.create_task(download_task())
            await asyncio.sleep(0.1)
            upload_fut = asyncio.create_task(upload_task())

            downloaded_data, _ = await asyncio.gather(download_fut, upload_fut)

            assert downloaded_data == test_payload, "Downloaded content does not match uploaded payload!"
            print("🎉 INTEGRITY CHECK PASSED: Uploaded and downloaded data are 100% byte-identical!")

            # 8. Report completion progress back to signaling
            await ws_a.send(json.dumps({
                "type": "UPDATE_PROGRESS",
                "file_id": file_id,
                "bytes_transferred": file_size,
                "status": "completed"
            }))

            progress_event_b = json.loads(await ws_b.recv())
            print(f"✅ Device B received final progress update for file {progress_event_b.get('file_id')}")

    print("\n============================================================")
    print("✨ ALL CROSS-NETWORK & RELAY TESTS PASSED SUCCESSFULLY!")
    print("============================================================")

if __name__ == "__main__":
    asyncio.run(run_test())
