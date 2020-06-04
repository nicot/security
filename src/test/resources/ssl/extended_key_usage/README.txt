# Commands to generate certs in this folder
# Root CA
openssl genrsa -out root-ca-key.pem 2048
openssl req -new -x509 -sha256 -key root-ca-key.pem -out root-ca.pem

# Node server cert
openssl genrsa -out node-key-server-temp.pem 2048
openssl pkcs8 -inform PEM -outform PEM -in node-key-server-temp.pem -topk8 -nocrypt -v1 PBE-SHA1-3DES -out node-key-server.pem
openssl req -new -key node-key-server.pem -out node-server.csr
openssl x509 -req -in node-server.csr -CA root-ca.pem -CAkey root-ca-key.pem -CAcreateserial -sha256 -extfile ext.cfg -extensions server_exts -out node-server.pem

# Node client cert
openssl genrsa -out node-key-client-temp.pem 2048
openssl pkcs8 -inform PEM -outform PEM -in node-key-client-temp.pem -topk8 -nocrypt -v1 PBE-SHA1-3DES -out node-key-client.pem
openssl req -new -key node-key-client.pem -out node-client.csr
openssl x509 -req -in node-client.csr -CA root-ca.pem -CAkey root-ca-key.pem -CAcreateserial -sha256 -extfile ext.cfg -extensions client_exts -out node-client.pem

# Cleanup
rm node-key-server-temp.pem
rm node-key-client-temp.pem
rm node-server.csr
rm node-client.csr