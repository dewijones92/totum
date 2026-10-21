#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <poll.h>
int main(int argc,char**argv){
  FILE*f=fopen(argv[1],"rb"); if(!f){perror("open");return 1;}
  unsigned char pkt[2048]; size_t n=fread(pkt,1,sizeof pkt,f); fclose(f);
  int s=socket(AF_INET,SOCK_DGRAM,0),one=1;
  setsockopt(s,SOL_SOCKET,SO_REUSEADDR,&one,4); setsockopt(s,SOL_SOCKET,SO_REUSEPORT,&one,4);
  struct sockaddr_in a={0}; a.sin_family=AF_INET; a.sin_port=htons(5353);
  if(bind(s,(void*)&a,sizeof a)){perror("bind");return 1;}
  struct ip_mreq m; m.imr_multiaddr.s_addr=inet_addr("224.0.0.251"); m.imr_interface.s_addr=INADDR_ANY;
  if(setsockopt(s,IPPROTO_IP,IP_ADD_MEMBERSHIP,&m,sizeof m)) perror("join");
  unsigned char ttl=255,loop=1; setsockopt(s,IPPROTO_IP,IP_MULTICAST_TTL,&ttl,1); setsockopt(s,IPPROTO_IP,IP_MULTICAST_LOOP,&loop,1);
  struct sockaddr_in g=a; g.sin_addr.s_addr=inet_addr("224.0.0.251");
  long sent=0,answered=0; time_t last=0;
  printf("dewidebug castbeacon replaying %zu bytes\n",n); fflush(stdout);
  for(;;){
    struct pollfd p={s,POLLIN,0}; poll(&p,1,1000);
    if(p.revents&POLLIN){unsigned char b[4096]; struct sockaddr_in fr; socklen_t fl=sizeof fr;
      ssize_t r=recvfrom(s,b,sizeof b,0,(void*)&fr,&fl);
      if(r>12 && !(b[2]&0x80) && memmem(b,r,"_googlecast",11)){
        sendto(s,pkt,n,0,(void*)&g,sizeof g); sendto(s,pkt,n,0,(void*)&fr,fl); answered++;
        printf("dewidebug castbeacon answered query from %s:%d answered=%ld\n",inet_ntoa(fr.sin_addr),ntohs(fr.sin_port),answered); fflush(stdout);}}
    if(time(0)-last>=2){ if(sendto(s,pkt,n,0,(void*)&g,sizeof g)<0) perror("send"); sent++; last=time(0);
      if(sent%30==1){printf("dewidebug castbeacon announced %ld\n",sent); fflush(stdout);}}
  }
}
