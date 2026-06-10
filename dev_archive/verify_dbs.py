import sqlite3
import hashlib

def get_table_hash(db_path, table_name, order_by):
    try:
        conn = sqlite3.connect(db_path)
        c = conn.cursor()
        c.execute(f"SELECT * FROM {table_name} ORDER BY {order_by}")
        rows = c.fetchall()
        
        hasher = hashlib.sha256()
        count = 0
        for row in rows:
            hasher.update(str(row).encode('utf-8'))
            count += 1
            
        conn.close()
        return count, hasher.hexdigest()
    except Exception as e:
        return 0, str(e)

for i in range(10):
    db = f"nodes/node_{i}.db"
    blocks_count, blocks_hash = get_table_hash(db, "blocks", "round")
    txs_count, txs_hash = get_table_hash(db, "transactions", "tx_id")
    state_count, state_hash = get_table_hash(db, "world_state", "address")
    
    print(f"Node {i}:")
    print(f"  Blocks:       {blocks_count} (Hash: {blocks_hash[:8]})")
    print(f"  Transactions: {txs_count} (Hash: {txs_hash[:8]})")
    print(f"  World State:  {state_count} (Hash: {state_hash[:8]})")
