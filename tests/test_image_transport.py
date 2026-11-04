"""Actual HTTP parser accepts bounded binary images without weakening JSON POST."""
import subprocess
from host_support import PROJECT_ROOT, CC, extract_c


def test_image_binary_framing_and_provider_authorities(tmp_path):
    source = (PROJECT_ROOT / 'app/buffer_app/buffer_https.c').read_text()
    harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <errno.h>
#define HEADER_LIMIT 8192u
#define BODY_LIMIT (64u*1024u)
#define IMAGE_BODY_LIMIT (256u*1024u)
struct endpoint {char host[64],port[6],path[1536];};
'''
    for signature in ('static int response_parse_bytes(', 'static int response_parse(', 'static int public_endpoint('):
        harness += extract_c(source, signature) + '\n'
    harness += r'''
int main(void) {
 struct endpoint ep;
 assert(!public_endpoint("https://api.ruseo.cn/api/baidu_image?words=%E8%8F%9C&limit=4&type=1",&ep));
 assert(!strcmp(ep.host,"api.ruseo.cn")&&!strcmp(ep.port,"443"));
 assert(!public_endpoint("https://img2.baidu.com/it/u=1&f=JPEG?w=320",&ep));
 const char *bad[]={"http://api.ruseo.cn/api/baidu_image?words=x","https://api.ruseo.cn.evil.test/api/baidu_image?words=x","https://127.0.0.1/api/baidu_image?words=x","https://img2.baidu.com@localhost/x","https://api.ruseo.cn/other?words=x","https://img2.baidu.com:80/x","https://img2.baidu.com/x\r\nInjected:yes"};
 for(unsigned i=0;i<sizeof(bad)/sizeof(bad[0]);i++)assert(public_endpoint(bad[i],&ep)<0);
 char wire[]="HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\na\0b";
 char *body=NULL;size_t length=0;int status=0;
 assert(response_parse(wire,sizeof(wire)-1,&body,&status)==-EPROTO&&!body);
 assert(!response_parse_bytes(wire,sizeof(wire)-1,&body,&status,true,&length));
 assert(status==200&&length==3&&!memcmp(body,"a\0b",3));free(body);body=NULL;
 char chunks[]="HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\na\0b\r\n0\r\n\r\n";
 assert(!response_parse_bytes(chunks,sizeof(chunks)-1,&body,&status,true,&length));
 assert(length==3&&!memcmp(body,"a\0b",3));free(body);body=NULL;
 char large[]="HTTP/1.1 200 OK\r\nContent-Length: 65537\r\n\r\n";
 assert(response_parse(large,sizeof(large)-1,&body,&status)==-EFBIG);
 assert(response_parse_bytes(large,sizeof(large)-1,&body,&status,true,&length)==-EAGAIN);
 char partial[]="HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\na\0b";
 assert(response_parse_bytes(partial,sizeof(partial)-1,&body,&status,true,&length)==-EAGAIN&&!body);
 char duplicate[]="HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Length: 3\r\n\r\na\0b";
 assert(response_parse_bytes(duplicate,sizeof(duplicate)-1,&body,&status,true,&length)==-EPROTO&&!body);
 return 0;
}
'''
    test = tmp_path / 'binary.c'
    test.write_text(harness)
    binary = tmp_path / 'binary'
    subprocess.run([*CC, '-std=c11', '-D_DEFAULT_SOURCE', '-Wall', '-Wextra', '-Werror', '-fsanitize=address,undefined', str(test), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=10)
