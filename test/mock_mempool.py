import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

# Global state to mock the mempool
mempool_queue = []
current_batch = []
current_batch_id = 1

class MempoolMockHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        global current_batch, current_batch_id, mempool_queue
        
        parsed_path = urlparse(self.path)
        if parsed_path.path == '/api/mempool/batch':
            # If we don't have an active batch, try to create one from the queue
            if len(current_batch) == 0 and len(mempool_queue) > 0:
                # Take up to 10 transactions
                current_batch = mempool_queue[:10]
                mempool_queue = mempool_queue[10:]
            
            response_data = {
                "batchId": current_batch_id,
                "transactions": current_batch
            }
            
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(response_data).encode('utf-8'))
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        global current_batch, current_batch_id, mempool_queue
        
        parsed_path = urlparse(self.path)
        content_length = int(self.headers.get('Content-Length', 0))
        post_data = self.rfile.read(content_length)
        
        if parsed_path.path == '/api/mempool/confirm':
            try:
                data = json.loads(post_data.decode('utf-8'))
                ack_batch_id = data.get('batchId', -1)
                
                if ack_batch_id == current_batch_id:
                    print(f"[Mempool] Batch {current_batch_id} confirmed and deleted.")
                    current_batch = []
                    current_batch_id += 1
                else:
                    print(f"[Mempool] Received confirm for batch {ack_batch_id}, but current is {current_batch_id}. Ignoring.")
                
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps({"status": "success"}).encode('utf-8'))
            except Exception as e:
                self.send_response(400)
                self.end_headers()
                
        elif parsed_path.path == '/inject':
            try:
                tx = json.loads(post_data.decode('utf-8'))
                # Basic validation
                if "type" not in tx or "txId" not in tx:
                    raise ValueError("Missing type or txId")
                
                mempool_queue.append(tx)
                print(f"[Mempool] Injected transaction {tx['txId']} into queue. Queue size: {len(mempool_queue)}")
                
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps({"status": "injected", "queueSize": len(mempool_queue)}).encode('utf-8'))
            except Exception as e:
                print(f"[Mempool] Failed to inject tx: {e}")
                self.send_response(400)
                self.end_headers()
                self.wfile.write(json.dumps({"error": str(e)}).encode('utf-8'))
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        # Suppress noisy standard HTTP logs
        pass

def run(server_class=HTTPServer, handler_class=MempoolMockHandler, port=8080):
    server_address = ('', port)
    httpd = server_class(server_address, handler_class)
    print(f"[Mempool Mock] Starting server on port {port}...")
    print(f"  - GET  /api/mempool/batch")
    print(f"  - POST /api/mempool/confirm")
    print(f"  - POST /inject (manual testing)")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    httpd.server_close()
    print("[Mempool Mock] Stopping server...\n")

if __name__ == '__main__':
    run()
