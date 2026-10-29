#!/usr/bin/env python3
"""A real SFTP server for the integration tests: sftp_test_server.py PORT ROOT HOSTKEYFILE.
Accepts user "bob" with password "pw", serves ROOT. Needs: pip install asyncssh"""
import asyncio, os, sys
import asyncssh


class Server(asyncssh.SSHServer):
    def begin_auth(self, username):
        return True

    def password_auth_supported(self):
        return True

    def validate_password(self, username, password):
        return username == 'bob' and password == 'pw'


async def main(port, root, keyfile):
    if not os.path.exists(keyfile):
        asyncssh.generate_private_key('ecdsa-sha2-nistp256').write_private_key(keyfile)
    await asyncssh.listen('127.0.0.1', port, server_factory=Server, server_host_keys=[keyfile],
                          sftp_factory=lambda chan: asyncssh.SFTPServer(chan, chroot=root))
    print('READY', flush=True)
    await asyncio.Event().wait()


if __name__ == '__main__':
    asyncio.run(main(int(sys.argv[1]), sys.argv[2], sys.argv[3]))
