import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

# Global state to mock the mempool
mempool_queue = []

class MempoolMockHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        global mempool_queue
        
        parsed_path = urlparse(self.path)
        if parsed_path.path == '/api/mempool/batch':
            # Just return the top 10 transactions without removing them from the queue
            # (Removal happens in /api/mempool/confirm_txs)
            top_txs = mempool_queue[:10]
            
            response_data = {
                "batchId": 0, # Legacy field, ignored by new implementation
                "transactions": top_txs
            }
            
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(response_data).encode('utf-8'))
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        global mempool_queue
        
        parsed_path = urlparse(self.path)
        content_length = int(self.headers.get('Content-Length', 0))
        post_data = self.rfile.read(content_length)
        
        if parsed_path.path == '/api/mempool/confirm_txs':
            try:
                data = json.loads(post_data.decode('utf-8'))
                tx_ids_to_remove = set(data.get('txIds', []))
                
                original_len = len(mempool_queue)
                mempool_queue = [tx for tx in mempool_queue if tx['txId'] not in tx_ids_to_remove]
                removed_count = original_len - len(mempool_queue)
                
                print(f"[Mempool] Confirmed and deleted {removed_count} transactions. Queue size: {len(mempool_queue)}")
                
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps({"status": "success", "removed": removed_count}).encode('utf-8'))
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
    print(f"  - POST /api/mempool/confirm_txs")
    print(f"  - POST /inject (manual testing)")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    httpd.server_close()
    print("[Mempool Mock] Stopping server...\n")

if __name__ == '__main__':
    run()
