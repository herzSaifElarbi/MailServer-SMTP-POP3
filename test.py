import socket
import time
from concurrent.futures import ThreadPoolExecutor

# Configuration for your servers
SMTP_SERVER = 'localhost'
SMTP_PORT = 25            # Use your configured SMTP port (e.g., 25 or 2525)
POP3_SERVER = 'localhost'
POP3_PORT = 110

def smtp_client(client_id):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect((SMTP_SERVER, SMTP_PORT))
        # Receive SMTP banner
        banner = s.recv(1024).decode()
        print(f"[SMTP Client {client_id}] Banner: {banner.strip()}")
        
        # Send HELO command
        s.sendall(b"HELO user1\r\n")
        resp = s.recv(1024).decode()
        print(f"[SMTP Client {client_id}] HELO: {resp.strip()}")
        
        # Send MAIL FROM
        s.sendall(b"MAIL FROM:<user1@example.com>\r\n")
        resp = s.recv(1024).decode()
        print(f"[SMTP Client {client_id}] MAIL FROM: {resp.strip()}")
        
        # Send RCPT TO
        s.sendall(b"RCPT TO:<user2@example.com>\r\n")
        resp = s.recv(1024).decode()
        print(f"[SMTP Client {client_id}] RCPT TO: {resp.strip()}")
        
        # Send DATA command
        s.sendall(b"DATA\r\n")
        resp = s.recv(1024).decode()
        print(f"[SMTP Client {client_id}] DATA: {resp.strip()}")
        
        # Send email content (subject and body) ending with a single dot on a line
        email_body = (
            f"Subject: Test Email from client {client_id}\r\n"
            "\r\n"
            "This is a test email sent automatically.\r\n"
            ".\r\n"
        )
        s.sendall(email_body.encode())
        resp = s.recv(1024).decode()
        print(f"[SMTP Client {client_id}] Email Sent: {resp.strip()}")
        
        # Send QUIT command
        s.sendall(b"QUIT\r\n")
        resp = s.recv(1024).decode()
        print(f"[SMTP Client {client_id}] QUIT: {resp.strip()}")
        s.close()
    except Exception as e:
        print(f"[SMTP Client {client_id}] Exception: {e}")

def pop3_client(client_id):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect((POP3_SERVER, POP3_PORT))
        s.settimeout(30)
        # Receive POP3 banner
        banner = s.recv(1024).decode()
        print(f"[POP3 Client {client_id}] Banner: {banner.strip()}")
        
        # Send USER command
        s.sendall(b"USER user2\r\n")
        resp = s.recv(1024).decode()
        print(f"[POP3 Client {client_id}] USER: {resp.strip()}")
        
        # Send PASS command
        s.sendall(b"PASS password2\r\n")
        resp = s.recv(1024).decode()
        print(f"[POP3 Client {client_id}] PASS: {resp.strip()}")
        
        # Send LIST command to get message list
        s.sendall(b"LIST\r\n")
        data = ""
        while True:
            chunk = s.recv(1024).decode()
            data += chunk
            if data.endswith("\r\n.\r\n") or data.endswith("\n.\n"):
                break
        #print(f"[POP3 Client {client_id}] LIST:\n{data.strip()}")
        
        # Retrieve message 1 (if exists)
        s.sendall(b"RETR 1\r\n")
        data = ""
        while True:
            chunk = s.recv(1024).decode()
            data += chunk
            if data.endswith("\r\n.\r\n") or data.endswith("\n.\n"):
                break
        #print(f"[POP3 Client {client_id}] RETR 1:\n{data.strip()}")
        
        # Send QUIT command
        s.sendall(b"QUIT\r\n")
        resp = s.recv(1024).decode()
        print(f"[POP3 Client {client_id}] QUIT: {resp.strip()}")
        s.close()
    except Exception as e:
        print(f"[POP3 Client {client_id}] Exception: {e}")

if __name__ == '__main__':
    num_smtp_clients = 100
    num_pop3_clients = 100
    with ThreadPoolExecutor(max_workers=25) as executor:
        # Launch SMTP clients to send emails
        for i in range(num_smtp_clients):
            executor.submit(smtp_client, i+1)
            executor.submit(pop3_client, i+1)
            
