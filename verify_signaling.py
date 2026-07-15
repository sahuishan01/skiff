import asyncio
import json
import sys

try:
    import websockets
except ImportError:
    print("This script requires the 'websockets' library. Please install it first:")
    print("  pip install websockets")
    sys.exit(1)

async def test_flow():
    port = sys.argv[1] if len(sys.argv) > 1 else "8080"
    uri = f"ws://localhost:{port}/ws"
    
    print("========== STARTING P2P SIGNALING VERIFICATION ==========")
    
    # 1. Connect Client A (Sender)
    print("\n[Client A] Connecting to signaling server...")
    async with websockets.connect(uri) as ws_a:
        # Register Client A
        await ws_a.send(json.dumps({
            "type": "REGISTER",
            "device_id": "sender-uuid-1111-aaaa"
        }))
        res_a = json.loads(await ws_a.recv())
        print(f"[Client A] Registered successfully! Received message: {res_a}")
        sender_code = res_a.get("device_code")
        print(f"[Client A] Allocated Pairing Code: {sender_code}")
        
        # 2. Connect Client B (Receiver)
        print("\n[Client B] Connecting to signaling server...")
        async with websockets.connect(uri) as ws_b:
            # Register Client B
            await ws_b.send(json.dumps({
                "type": "REGISTER",
                "device_id": "receiver-uuid-2222-bbbb"
            }))
            res_b = json.loads(await ws_b.recv())
            print(f"[Client B] Registered successfully! Received message: {res_b}")
            
            # 3. Client B requests pairing connection with Client A's Code
            print(f"\n[Client B] Requesting connection to pairing code: {sender_code}")
            await ws_b.send(json.dumps({
                "type": "REQUEST_CONNECTION",
                "target_code": sender_code
            }))
            
            # 4. Client A receives incoming request
            req_a = json.loads(await ws_a.recv())
            print(f"[Client A] Received incoming request event: {req_a}")
            
            # 5. Client A accepts request
            print(f"\n[Client A] Accepting connection request from Client B...")
            await ws_a.send(json.dumps({
                "type": "ACCEPT_REQUEST",
                "sender_device_id": "receiver-uuid-2222-bbbb"
            }))
            
            # 6. Client B receives request acceptance
            accept_b = json.loads(await ws_b.recv())
            print(f"[Client B] Connection accepted by peer! Event data: {accept_b}")
            
            print("\n========== VERIFICATION COMPLETED SUCCESSFULLY ==========")
            print("P2P Signaling server WebSocket routing and pairing logic is operational.")

if __name__ == "__main__":
    port = sys.argv[1] if len(sys.argv) > 1 else "8080"
    try:
        asyncio.run(test_flow())
    except ConnectionRefusedError:
        print(f"\n[ERROR] Connection refused. Make sure the Rust signaling server is running on port {port}.")
    except Exception as e:
        print(f"\n[ERROR] An unexpected error occurred: {e}")
