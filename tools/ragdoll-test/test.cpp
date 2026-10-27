// Gothic II Mobile: стенд регдолла (tools/ragdoll-test/build.sh).
// Проверка регдолла на компьютере: человек (Biped, T-поза) и волк; удары, бег, падение с высоты, вода.
#include "physics/ragdoll.h"
#include <cstdlib>
#include "physics/dynamicworld.h"
#include "graphics/mesh/skeleton.h"
#include "graphics/mesh/animmath.h"

#include <zenkit/Mesh.hh>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstdio>
#include <string>
#include <vector>

using namespace Tempest;

extern float waterLevel;
extern bool  meshGround;

struct Bone { const char* name; int parent; Vec3 at; };

static Vec3 org(const Matrix4x4& m) { return Vec3(m.at(3,0),m.at(3,1),m.at(3,2)); }
static Vec3 col(const Matrix4x4& m,int c) { return Vec3(m.at(c,0),m.at(c,1),m.at(c,2)); }

static Matrix4x4 frameAlong(Vec3 at, Vec3 x) {
  x = Vec3::normalize(x);
  Vec3 up = std::abs(x.y)<0.9f ? Vec3(0,1,0) : Vec3(0,0,1);
  Vec3 z = Vec3::normalize(Vec3::crossProduct(x,up));
  Vec3 y = Vec3::crossProduct(z,x);
  Matrix4x4 m = Matrix4x4::mkIdentity();
  m.set(0,0,x.x); m.set(0,1,x.y); m.set(0,2,x.z);
  m.set(1,0,y.x); m.set(1,1,y.y); m.set(1,2,y.z);
  m.set(2,0,z.x); m.set(2,1,z.y); m.set(2,2,z.z);
  m.set(3,0,at.x); m.set(3,1,at.y); m.set(3,2,at.z);
  return m;
  }

static std::unique_ptr<Skeleton> mkSkeleton(const std::vector<Bone>& b, const char* name) {
  zenkit::ModelHierarchy h;
  auto sk = std::make_unique<Skeleton>(h,nullptr,name);
  sk->nodes.resize(b.size());
  sk->tr.resize(b.size());
  for(size_t i=0;i<b.size();++i) {
    // ось X кости - к первому потомку (как у Biped), у концов - продолжение родителя
    Vec3 dir = Vec3(0,1,0);
    for(size_t c=0;c<b.size();++c)
      if(b[c].parent==int(i) && (b[c].at-b[i].at).length()>0.1f) { dir = b[c].at-b[i].at; break; }
    if(dir.y==1 && b[i].parent>=0)
      dir = b[i].at-b[size_t(b[i].parent)].at;
    sk->tr[i] = frameAlong(b[i].at,dir);
    }
  for(size_t i=0;i<b.size();++i) {
    sk->nodes[i].name   = b[i].name;
    sk->nodes[i].parent = b[i].parent<0 ? size_t(-1) : size_t(b[i].parent);
    if(b[i].parent<0) {
      sk->nodes[i].tr = sk->tr[i];
      sk->rootNodes.push_back(i);
      } else {
      Matrix4x4 inv = sk->tr[size_t(b[i].parent)];
      inv.inverse();
      sk->nodes[i].tr = inv*sk->tr[i];
      }
    }
  return sk;
  }

static std::vector<Bone> human() {
  std::vector<Bone> b = {
    {"BIP01",-1,{0,95,0}}, {"BIP01 PELVIS",0,{0,95,0}}, {"BIP01 SPINE",1,{0,105,0}},
    {"BIP01 L THIGH",2,{10,92,0}}, {"BIP01 L CALF",3,{10,50,0}}, {"BIP01 L FOOT",4,{10,8,0}}, {"BIP01 L TOE0",5,{10,0,15}},
    {"BIP01 R THIGH",2,{-10,92,0}},{"BIP01 R CALF",7,{-10,50,0}},{"BIP01 R FOOT",8,{-10,8,0}},{"BIP01 R TOE0",9,{-10,0,15}},
    {"BIP01 SPINE1",2,{0,118,0}}, {"BIP01 SPINE2",11,{0,133,0}}, {"BIP01 NECK",12,{0,152,0}}, {"BIP01 HEAD",13,{0,160,0}},
    {"ZS_HELMET",14,{0,175,0}},
    {"BIP01 L CLAVICLE",13,{3,150,0}}, {"BIP01 L UPPERARM",16,{18,148,0}}, {"BIP01 L FOREARM",17,{46,148,0}},
    {"BIP01 L HAND",18,{72,148,0}}, {"BIP01 L FINGER1",19,{90,148,0}}, {"ZS_LEFTHAND",19,{78,148,0}},
    {"BIP01 R CLAVICLE",13,{-3,150,0}}, {"BIP01 R UPPERARM",22,{-18,148,0}}, {"BIP01 R FOREARM",23,{-46,148,0}},
    {"BIP01 R HAND",24,{-72,148,0}}, {"BIP01 R FINGER1",25,{-90,148,0}}, {"ZS_RIGHTHAND",25,{-78,148,0}},
    };
  return b;
  }

static std::vector<Bone> wolf() {
  std::vector<Bone> b = {
    {"BIP01",-1,{0,60,0}}, {"BIP01 PELVIS",0,{0,60,-30}}, {"BIP01 SPINE",1,{0,62,-20}}, {"BIP01 SPINE1",2,{0,63,0}},
    {"BIP01 SPINE2",3,{0,64,20}}, {"BIP01 NECK",4,{0,66,35}}, {"BIP01 HEAD",5,{0,75,50}}, {"BIP01 HEAD NUB",6,{0,72,72}},
    {"BIP01 L THIGH",2,{8,58,-32}}, {"BIP01 L CALF",8,{8,35,-28}}, {"BIP01 L FOOT",9,{8,12,-36}}, {"BIP01 L TOE0",10,{8,0,-30}},
    {"BIP01 R THIGH",2,{-8,58,-32}},{"BIP01 R CALF",12,{-8,35,-28}},{"BIP01 R FOOT",13,{-8,12,-36}},{"BIP01 R TOE0",14,{-8,0,-30}},
    {"BIP01 L CLAVICLE",5,{4,62,22}}, {"BIP01 L UPPERARM",16,{8,55,25}}, {"BIP01 L FOREARM",17,{8,32,27}},
    {"BIP01 L HAND",18,{8,8,26}}, {"BIP01 L FINGER0",19,{8,0,32}},
    {"BIP01 R CLAVICLE",5,{-4,62,22}},{"BIP01 R UPPERARM",21,{-8,55,25}},{"BIP01 R FOREARM",22,{-8,32,27}},
    {"BIP01 R HAND",23,{-8,8,26}}, {"BIP01 R FINGER0",24,{-8,0,32}},
    {"BIP01 TAIL",1,{0,62,-38}}, {"BIP01 TAIL1",26,{0,60,-55}}, {"BIP01 TAIL2",27,{0,55,-70}}, {"BIP01 TAIL NUB",28,{0,50,-82}},
    };
  return b;
  }

struct Result {
  bool  ok = true;
  float maxSep = 0, maxV = 0, pelvisY = 0, frozenAt = -1, chestFrontY = 0;
  float kneeMin = 999, kneeMax = -999, elbowMin = 999, elbowMax = -999;
  };

// сгиб колена/локтя: + в сторону, куда сгибается сустав (bend), относительно позы привязки
static float flexOf(const Skeleton& sk, const std::vector<Matrix4x4>& w, size_t a, size_t b, size_t c, Vec3 bendObj) {
  // ось шарнира в системе кости a: cross(dP, bend) в позе привязки
  const Vec3 dP0 = Vec3::normalize(org(sk.tr[b])-org(sk.tr[a]));
  Vec3 ax0 = Vec3::normalize(Vec3::crossProduct(dP0,bendObj));
  const auto& ra = sk.tr[a];
  Vec3 axL = Vec3(Vec3::dotProduct(Vec3::normalize(col(ra,0)),ax0),Vec3::dotProduct(Vec3::normalize(col(ra,1)),ax0),Vec3::dotProduct(Vec3::normalize(col(ra,2)),ax0));
  Vec3 axW = Vec3::normalize(col(w[a],0))*axL.x + Vec3::normalize(col(w[a],1))*axL.y + Vec3::normalize(col(w[a],2))*axL.z;
  Vec3 dP = Vec3::normalize(org(w[b])-org(w[a]));
  Vec3 dC = Vec3::normalize(org(w[c])-org(w[b]));
  return std::atan2(Vec3::dotProduct(Vec3::crossProduct(dP,dC),axW), Vec3::dotProduct(dP,dC))*180.f/float(M_PI);
  }

static Result run(DynamicWorld& dw, const Skeleton& sk, const char* title, Vec3 lift, Vec3 vel, Ragdoll::Hit hit,
                  float seconds, bool human, int fps, Ragdoll::State st = Ragdoll::S_Dead) {
  std::vector<Matrix4x4> bones(sk.tr.size());
  Matrix4x4 obj = Matrix4x4::mkIdentity();
  obj.translate(lift);
  for(size_t i=0;i<bones.size();++i)
    bones[i] = obj*sk.tr[i];
  auto r = Ragdoll::create(dw,sk,bones.data(),bones.size(),st);
  Result res;
  if(!r) { std::printf("%s: create failed\n",title); res.ok=false; return res; }
  r->setVelocity(vel);
  if(hit.power>0) {
    hit.at = hit.at + lift;
    r->hit(hit);
    }

  const uint64_t dt = uint64_t(1000/fps);
  // все кости по иерархии: ведомые - из куклы, прочие - локально от родителя (поза привязки)
  std::vector<Matrix4x4> w(sk.tr.size());
  std::vector<Matrix4x4> prev;
  auto& drv = r->driven();
  for(float t=0; t<seconds; t+=float(dt)/1000.f) {
    dw.tick(dt);
    for(size_t i=0;i<sk.nodes.size();++i) {
      const size_t p = sk.nodes[i].parent;
      w[i] = (p<w.size() ? w[p] : obj)*sk.nodes[i].tr;
      if(drv[i]) {
        for(size_t k=0;k<r->bones().size();++k)
          if(r->bones()[k]==i) w[i] = r->world()[k];
        }
      }
    // расхождение суставов: ведомая кость против предсказания от родителя
    for(size_t k=1;k<r->driven().size() && k<r->bones().size();++k) {
      if(r->bones()[k]==0) continue; // корень BIP01 идёт за тазом
      const size_t i = r->bones()[k];
      // часть-родитель: ближайший ведомый предок (бедро - к тазу)
      size_t pb = r->bones()[0];
      const std::string nm = sk.nodes[i].name;
      if(nm.find("THIGH")==std::string::npos)
        for(size_t p=sk.nodes[i].parent; p<w.size(); p=sk.nodes[p].parent) {
          const std::string pn = sk.nodes[p].name;
          if(nm.find("UPPERARM")!=std::string::npos && (pn.find("NECK")!=std::string::npos || pn.find("HEAD")!=std::string::npos))
            continue;
          if(drv[p]) { pb = p; break; }
          }
      Matrix4x4 inv = sk.tr[pb]; inv.inverse();
      Matrix4x4 pred = w[pb]*inv*sk.tr[i];
      float sep = (org(pred)-org(w[i])).length();
      res.maxSep = std::max(res.maxSep,sep);
      }
    for(auto& m:r->world()) {
      Vec3 o = org(m);
      if(!std::isfinite(o.x) || !std::isfinite(o.y) || !std::isfinite(o.z)) res.ok=false;
      }
    if(human) {
      float kl = flexOf(sk,w,3,4,5,Vec3(0,0,-1)), kr = flexOf(sk,w,7,8,9,Vec3(0,0,-1));
      float el = flexOf(sk,w,17,18,19,Vec3(0,0,1)), er = flexOf(sk,w,23,24,25,Vec3(0,0,1));
      res.kneeMin = std::min({res.kneeMin,kl,kr});  res.kneeMax = std::max({res.kneeMax,kl,kr});
      res.elbowMin = std::min({res.elbowMin,el,er}); res.elbowMax = std::max({res.elbowMax,el,er});
      }
    if(!r->isSimulated() && res.frozenAt<0) { res.frozenAt = t; if(std::getenv("TRACE")) std::printf("   frozen at %.2f\n",double(t)); }
    {
      float mv=0, mw=0; size_t worst=0;
      if(prev.size()==r->world().size())
        for(size_t k=0;k<prev.size();++k) {
          float v = (org(r->world()[k])-org(prev[k])).length()/float(dt)*10.f;
          float a = (Vec3::normalize(col(r->world()[k],0))-Vec3::normalize(col(prev[k],0))).length()/float(dt)*1000.f;
          if(v>mv) { mv=v; worst=k; }
          mw=std::max(mw,a);
          }
      prev = r->world();
      if(std::getenv("TRACE") && int(t*1000)%500<int(dt))
        std::printf("   t=%.1f sim=%d v=%.3f w=%.3f worst=%s\n",double(t),int(r->isSimulated()),double(mv),double(mw),sk.nodes[r->bones()[worst]].name.c_str());
    }
    }
  res.pelvisY     = r->rootPosition().y;
  res.chestFrontY = r->chestFront().y;
  Vec3 p = r->rootPosition();
  std::printf("%-34s ok=%d frozen=%.2fs pelvis=(%.0f,%.0f,%.0f) chestFront.y=%+.2f maxJointSep=%.1fcm",
              title,int(res.ok),double(res.frozenAt),double(p.x),double(p.y),double(p.z),double(res.chestFrontY),double(res.maxSep));
  if(human)
    std::printf(" knee[%.0f..%.0f] elbow[%.0f..%.0f]",double(res.kneeMin),double(res.kneeMax),double(res.elbowMin),double(res.elbowMax));
  std::printf("\n");
  return res;
  }

static zenkit::AnimationSample toSampleCopy(const Matrix4x4& m);

// ---- активный регдолл: цель - поза привязки (T-поза), объект - сдвиг и поворот
static std::vector<Matrix4x4> poseAt(const Skeleton& sk, const Matrix4x4& obj) {
  std::vector<Matrix4x4> w(sk.tr.size());
  for(size_t i=0;i<w.size();++i) w[i] = obj*sk.tr[i];
  return w;
  }

static Matrix4x4 objAt(Vec3 at, float rotY = 0) {
  Matrix4x4 m = Matrix4x4::mkIdentity();
  m.translate(at);
  m.rotateOY(rotY);
  return m;
  }

// Поза с замахом правой рукой: плечо и всё ниже поворачиваются вокруг плечевого сустава (вверх-вниз), рад
static std::vector<Matrix4x4> poseSwing(const Skeleton& sk, const Matrix4x4& obj, float angle) {
  std::vector<Matrix4x4> w(sk.tr.size());
  size_t arm = 0;
  for(size_t i=0;i<sk.nodes.size();++i) if(sk.nodes[i].name=="BIP01 R UPPERARM") arm = i;
  const Vec3 sh = org(sk.tr[arm]);
  Matrix4x4 rot = Matrix4x4::mkIdentity();
  rot.translate(sh);
  rot.rotate(angle*180.f/float(M_PI), 0, 0, 1);
  rot.translate(-sh);
  for(size_t i=0;i<w.size();++i) {
    bool sub = false;
    for(size_t p=i; p<sk.nodes.size(); p=sk.nodes[p].parent) if(p==arm) { sub = true; break; }
    w[i] = sub ? obj*rot*sk.tr[i] : obj*sk.tr[i];
    }
  return w;
  }

// Отклонение ведомых костей от цели относительно таза (так их показывает Pose в активном режиме), см
static float relDeviation(const Ragdoll& r, const std::vector<Matrix4x4>& target) {
  const auto& b = r.bones();
  const auto& w = r.world();
  Matrix4x4 ip = w[0];        ip.inverse();
  Matrix4x4 it = target[b[0]]; it.inverse();
  float dev = 0;
  for(size_t k=1;k<b.size();++k) {
    const Vec3 a = org(ip*w[k]), c = org(it*target[b[k]]);
    dev = std::max(dev,(a-c).length());
    }
  return dev;
  }

static float absDeviation(const Ragdoll& r, const std::vector<Matrix4x4>& target) {
  const auto& b = r.bones();
  const auto& w = r.world();
  float dev = 0;
  for(size_t k=0;k<b.size();++k)
    dev = std::max(dev,(org(w[k])-org(target[b[k]])).length());
  return dev;
  }

static int activeTests(DynamicWorld& dw, const Skeleton& hum, const Skeleton& wlf) {
  int fail = 0;
  auto ok = [&](bool v, const char* what) { if(!v) { fail++; std::printf("   ^^^ FAIL: %s\n",what); } };

  // 1) стоит в позе анимации: держит её (руки в стороны - против тяжести)
  {
    Matrix4x4 obj = objAt(Vec3(0,0,-600));
    auto pose = poseAt(hum,obj);
    auto r = Ragdoll::create(dw,hum,pose.data(),pose.size(),Ragdoll::S_Active);
    r->setTarget(pose,0);
    float dev = 0;
    for(int f=0; f<60; ++f) { r->setTarget(pose,33); dw.tick(33); if(f>10) dev = std::max(dev,absDeviation(*r,pose)); }
    std::printf("active: holds pose               max deviation %.1f cm\n",double(dev));
    ok(dev<3.f,"holds pose");
  }
  // 2) бежит 5 м/с: относительно таза - как в анимации
  {
    auto r = Ragdoll::create(dw,hum,poseAt(hum,objAt(Vec3(0,0,-600))).data(),hum.tr.size(),Ragdoll::S_Active);
    r->setVelocity(Vec3(0,0,5)); // как Npc: кукла начата на бегу
    float dev = 0, absDev = 0;
    for(int f=0; f<60; ++f) {
      auto pose = poseAt(hum,objAt(Vec3(0,0,-600+float(f)*16.5f)));
      r->setTarget(pose, f==0 ? 0 : 33);
      dw.tick(33);
      auto next = poseAt(hum,objAt(Vec3(0,0,-600+float(f+1)*16.5f)));
      if(f>10) { dev = std::max(dev,relDeviation(*r,next)); absDev = std::max(absDev,absDeviation(*r,next)); }
      }
    std::printf("active: runs 5 m/s               relative %.1f cm, absolute %.1f cm\n",double(dev),double(absDev));
    ok(dev<3.f,"runs: relative pose");
  }
  // 2б) разворот на месте 180 град/с и быстрый замах рукой (850 град/с в пике): тело успевает за анимацией
  {
    auto r = Ragdoll::create(dw,hum,poseAt(hum,objAt(Vec3(-300,0,-600))).data(),hum.tr.size(),Ragdoll::S_Active);
    float dev = 0;
    for(int f=0; f<60; ++f) {
      r->setTarget(poseAt(hum,objAt(Vec3(-300,0,-600),float(f)*6.f)), f==0 ? 0 : 33);
      dw.tick(33);
      if(f>5) dev = std::max(dev,relDeviation(*r,poseAt(hum,objAt(Vec3(-300,0,-600),float(f+1)*6.f))));
      }
    std::printf("active: turns 180 deg/s           relative %.1f cm\n",double(dev));
    ok(dev<3.f,"turns: relative pose");
    auto q = Ragdoll::create(dw,hum,poseAt(hum,objAt(Vec3(-300,0,-900))).data(),hum.tr.size(),Ragdoll::S_Active);
    float sw = 0;
    for(int f=0; f<60; ++f) {
      auto ang = [](int k) { return 1.57f*std::sin(float(k)*0.033f*2.f*float(M_PI)*1.5f); };
      q->setTarget(poseSwing(hum,objAt(Vec3(-300,0,-900)),ang(f)), f==0 ? 0 : 33);
      dw.tick(33);
      if(f>5) sw = std::max(sw,relDeviation(*q,poseSwing(hum,objAt(Vec3(-300,0,-900)),ang(f+1))));
      }
    // физика отстаёт от быстрого замаха меньше, чем на кадр движения (34 см); на экране без удара - анимация
    std::printf("active: weapon swing 850 deg/s    hand behind animation %.1f cm, shown %.2f\n",double(sw),double(q->showWeight()));
    ok(sw<30.f,"swing: arm follows a fast swing"); ok(q->showWeight()==0.f,"swing: animation on screen without hits");
  }
  // 3) удар спереди в грудь: тело вздрагивает и возвращается в анимацию, равновесие на месте
  {
    Matrix4x4 obj = objAt(Vec3(300,0,-600));
    auto pose = poseAt(hum,obj);
    auto r = Ragdoll::create(dw,hum,pose.data(),pose.size(),Ragdoll::S_Active);
    r->setTarget(pose,0);
    for(int f=0; f<10; ++f) { r->setTarget(pose,33); dw.tick(33); }
    Ragdoll::Hit h; h.at = org(pose[12]); h.dir = Vec3(0,0,-1); h.power = 3.f;
    r->hitActive(h,0);
    float peak = 0, back = 0, shown = 0;
    for(int f=0; f<45; ++f) {
      r->setTarget(pose,33); dw.tick(33);
      const float d = relDeviation(*r,pose);
      if(f<12) { peak = std::max(peak,d); shown = std::max(shown,r->showWeight()); }
      if(f>=36) back = std::max(back,d);
      }
    ok(shown==1.f && r->showWeight()==0.f,"hit: physics shown during the reaction only");
    std::printf("active: hit chest 3 m/s           reaction %.1f cm, after 1.2 s %.1f cm, balance %s\n",
                double(peak),double(back),r->lostBalance() ? "lost" : "kept");
    ok(peak>6.f,"hit: visible reaction"); ok(back<2.f,"hit: back to animation"); ok(!r->lostBalance(),"hit: balance");
  }
  // 4) пошатывание: обычный удар - устоял, сильный - падает
  auto stagger = [&](float power, Vec3 hdir) {
    Matrix4x4 obj = objAt(Vec3(600,0,-600));
    auto pose = poseAt(hum,obj);
    auto r = Ragdoll::create(dw,hum,pose.data(),pose.size(),Ragdoll::S_Active);
    r->setTarget(pose,0);
    for(int f=0; f<10; ++f) { r->setTarget(pose,33); dw.tick(33); }
    Ragdoll::Hit h; h.at = org(pose[12]); h.dir = hdir; h.power = power;
    r->hitActive(h,900);
    bool lost = false;
    for(int f=0; f<30 && !lost; ++f) { r->setTarget(pose,33); dw.tick(33); lost = r->lostBalance(); }
    return lost;
    };
  for(float pw : {2.f,3.f,4.f,5.f,6.f,8.f}) {
    const bool lost = stagger(pw,Vec3(0,0,-1));
    std::printf("active: stagger, hit %.0f m/s      %s\n",double(pw),lost ? "falls" : "keeps balance");
    if(pw<=4.f) ok(!lost,"stagger: weak hit must not knock down");
    if(pw>=6.f) ok(lost,"stagger: strong hit must knock down");
    }
  // сзади и сбоку - так же: обычный удар не валит, сильный - валит
  for(Vec3 d : {Vec3(0,0,1),Vec3(1,0,0)}) {
    const bool weak = stagger(3.f,d), strong = stagger(7.f,d);
    std::printf("active: stagger from %s       3 m/s %s, 7 m/s %s\n", d.z>0 ? "behind" : "side  ",
                weak ? "falls" : "keeps", strong ? "falls" : "keeps");
    ok(!weak,"stagger: weak hit from behind/side"); ok(strong,"stagger: strong hit from behind/side");
    }
  // 5) упал с ног: суставы с пределами, тело ложится, без NaN
  {
    Matrix4x4 obj = objAt(Vec3(900,0,-600));
    auto pose = poseAt(hum,obj);
    auto r = Ragdoll::create(dw,hum,pose.data(),pose.size(),Ragdoll::S_Active);
    r->setTarget(pose,0);
    for(int f=0; f<5; ++f) { r->setTarget(pose,33); dw.tick(33); }
    Ragdoll::Hit h; h.at = org(pose[12]); h.dir = Vec3(0,0,-1); h.power = 6.f;
    r->hitActive(h,900);
    for(int f=0; f<30 && !r->lostBalance(); ++f) { r->setTarget(pose,33); dw.tick(33); }
    r->setState(Ragdoll::S_Fall);
    float impact = 0; bool finite = true;
    for(int f=0; f<120; ++f) {
      r->setTarget(pose,33); dw.tick(33); impact = std::max(impact,r->takeImpact());
      for(auto& m:r->world()) { Vec3 o = org(m); finite &= std::isfinite(o.x) && std::isfinite(o.y) && std::isfinite(o.z); }
      }
    std::printf("active -> fall: pelvis %.0f cm, chestFront.y %+.2f, impact %.1f m/s\n",
                double(r->rootPosition().y),double(r->chestFront().y),double(impact));
    ok(finite,"fall: finite"); ok(r->rootPosition().y<40.f,"fall: lies on ground");
  }
  // 6) живой падает вперёд с 2 м: руки к земле до удара; с 15 м - машет руками
  {
    Matrix4x4 obj = objAt(Vec3(0,200,600));
    auto pose = poseAt(hum,obj);
    auto r = Ragdoll::create(dw,hum,pose.data(),pose.size(),Ragdoll::S_Fall);
    r->setVelocity(Vec3(0,0,3));
    Ragdoll::Hit h; h.at = org(pose[12]); h.dir = Vec3(0,0,1); h.power = 2.f;
    r->hit(h);
    float reach = -1;
    for(int f=0; f<40; ++f) {
      r->setTarget(pose,33); dw.tick(33);
      if(r->rootPosition().y>45.f && r->isAirborne())
        for(size_t k=0;k<r->bones().size();++k) {
          const std::string nm = hum.nodes[r->bones()[k]].name;
          if(nm.find("UPPERARM")==std::string::npos) continue;
          reach = std::max(reach, -Vec3::normalize(col(r->world()[k],0)).y);
          }
      }
    std::printf("fall 2 m forward: arms toward ground %.2f (1 - straight down)\n",double(reach));
    ok(reach>0.5f,"fall: arms reach for the ground");
  }
  {
    Matrix4x4 obj = objAt(Vec3(-600,1500,600));
    auto pose = poseAt(hum,obj);
    auto r = Ragdoll::create(dw,hum,pose.data(),pose.size(),Ragdoll::S_Fall);
    Vec3 first; bool have = false; float turn = 0;
    for(int f=0; f<45; ++f) {
      r->setTarget(pose,33); dw.tick(33);
      for(size_t k=0;k<r->bones().size();++k) {
        const std::string nm = hum.nodes[r->bones()[k]].name;
        if(nm!="BIP01 L UPPERARM") continue;
        Matrix4x4 ip = r->world()[0]; ip.inverse();
        Vec3 d = Vec3::normalize(col(ip*r->world()[k],0));
        if(!have) { first = d; have = true; }
        turn = std::max(turn, std::acos(std::clamp(Vec3::dotProduct(first,d),-1.f,1.f))*180.f/float(M_PI));
        }
      }
    std::printf("fall 15 m: arm swings %.0f deg in the air\n",double(turn));
    ok(turn>90.f,"fall: flails in a long fall");
    for(int f=0; f<200 && r->isSimulated(); ++f) { r->setTarget(pose,33); dw.tick(33); }
  }
  // 7) убитый с позой анимации: оседает и ложится (мышцы слабеют)
  {
    Matrix4x4 obj = objAt(Vec3(-600,0,-600));
    auto pose = poseAt(hum,obj);
    auto r = Ragdoll::create(dw,hum,pose.data(),pose.size(),Ragdoll::S_Dead);
    Ragdoll::Hit h; h.at = org(pose[12]); h.dir = Vec3(0,0,-1); h.power = 3.f;
    r->hit(h);
    for(int f=0; f<120 && r->isSimulated(); ++f) { r->setTarget(pose,33); dw.tick(33); }
    std::printf("dead with animation target: pelvis %.0f cm, simulated %d\n",double(r->rootPosition().y),int(r->isSimulated()));
    ok(r->rootPosition().y<40.f && !r->isSimulated(),"dead: lies and freezes");
  }
  // 8) волк на ногах: лапы держатся, удар сбоку - тело вздрагивает
  {
    Matrix4x4 obj = objAt(Vec3(-300,0,-900));
    auto pose = poseAt(wlf,obj);
    auto r = Ragdoll::create(dw,wlf,pose.data(),pose.size(),Ragdoll::S_Active);
    r->setTarget(pose,0);
    for(int f=0; f<10; ++f) { r->setTarget(pose,33); dw.tick(33); }
    Ragdoll::Hit h; h.at = Vec3(-300+5,63,-900); h.dir = Vec3(-1,0,0); h.power = 3.f;
    r->hitActive(h,0);
    float peak = 0, back = 0;
    for(int f=0; f<45; ++f) {
      r->setTarget(pose,33); dw.tick(33);
      const float d = relDeviation(*r,pose);
      if(f<12) peak = std::max(peak,d);
      if(f>=36) back = std::max(back,d);
      }
    std::printf("active wolf: hit side            reaction %.1f cm, after 1.2 s %.1f cm\n",double(peak),double(back));
    ok(peak>4.f && back<2.5f,"wolf: reacts and returns");
  }
  return fail;
  }

static std::unique_ptr<Ragdoll> spawn(DynamicWorld& dw, const Skeleton& sk, Vec3 at, float rotY, Ragdoll::State st,
                                      Vec3 hitDir, float power) {
  std::vector<Matrix4x4> bones(sk.tr.size());
  Matrix4x4 obj = Matrix4x4::mkIdentity();
  obj.translate(at);
  obj.rotateOY(rotY);
  for(size_t k=0;k<bones.size();++k) bones[k] = obj*sk.tr[k];
  auto r = Ragdoll::create(dw,sk,bones.data(),bones.size(),st);
  if(r && power>0) {
    Ragdoll::Hit h; h.at = org(bones[12]); h.dir = hitDir; h.power = power;
    r->hit(h);
    }
  return r;
  }

// Нагрузка на земле из треугольников (как земля мира игры): N тел падают от ударов, мс на кадр, пока есть куклы.
// Запуск: ragtest perf
static int perf() {
  zenkit::Mesh mesh;
  DynamicWorld dw(nullptr,mesh);
  auto hum = mkSkeleton(human(),"HUMANS.MDH");
  auto wlf = mkSkeleton(wolf(),"WOLF.MDH");
  auto bench = [&](const char* title, int n, float dist) {
    std::vector<std::unique_ptr<Ragdoll>> rs;
    for(int i=0;i<n;++i) {
      auto r = spawn(dw, i%3==2 ? *wlf : *hum, Vec3(float(i%4)*150.f-200.f, 0, float(i/4)*150.f), float(i)*40.f,
                     Ragdoll::S_Dead, Vec3(std::cos(float(i)),0,std::sin(float(i))), 3.5f);
      if(r==nullptr)
        continue;
#ifndef OLD_API
      r->setViewDistance(dist,false);
#else
      (void)dist;
#endif
      rs.push_back(std::move(r));
      }
    double total = 0, worst = 0; int frames = 0; float last = 0;
    for(int f=0; f<400; ++f) {
      size_t sim = 0;
      for(auto& r:rs) sim += r->isSimulated();
      if(sim==0) break;
      auto t0 = std::chrono::steady_clock::now();
      dw.tick(33);
      double ms = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
      total += ms; worst = std::max(worst,ms); frames++; last = float(f+1)*0.033f;
      }
    std::printf("%-30s %5.2f ms/frame avg, worst %5.2f ms, physics %.2f s, total %.0f ms\n",
                title, total/std::max(frames,1), worst, double(last), total);
    };
  // 4 живых на ногах бегут и разворачиваются (мышцы и закрепление), удар каждые 0,5 с
  auto benchActive = [&](const char* title, int n) {
    std::vector<std::unique_ptr<Ragdoll>> rs;
    for(int i=0;i<n;++i)
      rs.push_back(Ragdoll::create(dw,*hum,poseAt(*hum,objAt(Vec3(float(i)*200.f-300.f,0,-1500))).data(),hum->tr.size(),
                                   Ragdoll::S_Active,500,false));
    double total = 0, worst = 0;
    for(int f=0; f<90; ++f) {
      for(int i=0;i<n;++i) {
        if(rs[size_t(i)]==nullptr) continue;
        auto pose = poseAt(*hum,objAt(Vec3(float(i)*200.f-300.f,0,-1500+float(f)*10.f),float(f)*3.f));
        rs[size_t(i)]->setTarget(pose, f==0 ? 0 : 33);
        if(f%15==i) { Ragdoll::Hit h; h.at = org(pose[12]); h.dir = Vec3(0,0,-1); h.power = 3.f; rs[size_t(i)]->hitActive(h,0); }
        }
      auto t0 = std::chrono::steady_clock::now();
      dw.tick(33);
      double ms = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
      total += ms; worst = std::max(worst,ms);
      }
    std::printf("%-30s %5.2f ms/frame avg, worst %5.2f ms\n", title, total/90.0, worst);
    };
  for(int k=0;k<2;++k) {
    benchActive("4 alive on feet (active)", 4);
    bench("1 body, near",          1,  500);
    bench("4 bodies, near",        4,  500);
    bench("4 bodies, 20 m",        4, 2000);
    bench("4 bodies, far (32 m)",  4, 3200);
    bench("8 bodies, near",        8,  500);
    }
  return 0;
  }

int main(int argc, char** argv) {
  if(argc>1 && std::string(argv[1])=="perf") {
    meshGround = true;
    return perf();
    }
  zenkit::Mesh mesh;
  DynamicWorld dw(nullptr,mesh);
  auto hum = mkSkeleton(human(),"HUMANS.MDH");
  auto wlf = mkSkeleton(wolf(),"WOLF.MDH");

  Ragdoll::Hit none;
  Ragdoll::Hit front; front.at = Vec3(0,135,5);  front.dir = Vec3(0,0,-1); front.power = 3.f;   // удар спереди в грудь
  Ragdoll::Hit back;  back .at = Vec3(0,135,-5); back .dir = Vec3(0,0, 1); back .power = 3.f;
  Ragdoll::Hit side;  side .at = Vec3(5,63,0);   side .dir = Vec3(-1,0,0); side .power = 3.f;
  Ragdoll::Hit fly;   fly  .at = Vec3(0,120,5);  fly  .dir = Vec3(0,0,-1); fly  .power = 8.f;

  int fail = 0;
  auto check = [&](const Result& r, bool human, float frontSign) {
    // застывает не позже срока в физике (Ragdoll::LiveTime), а не лежит в физике до полной остановки
    bool ok = r.ok && r.frozenAt>0 && r.frozenAt<=float(Ragdoll::LiveTime)/1000.f+0.05f &&
              r.maxSep<6.f && r.pelvisY<40.f && r.pelvisY>-5.f;
    if(human) ok = ok && r.kneeMin>-12 && r.kneeMax<155 && r.elbowMin>-16 && r.elbowMax<160;
    if(frontSign!=0) ok = ok && (r.chestFrontY*frontSign>0.2f);
    if(!ok) { fail++; std::printf("   ^^^ FAIL\n"); }
    };
  check(run(dw,*hum,"human: hit front (falls on back)",Vec3(0,0,0),Vec3(),front,8,true,30), true, +1);
  check(run(dw,*hum,"human: hit back (falls on face)",Vec3(0,0,0),Vec3(),back,8,true,30), true, -1);
  check(run(dw,*hum,"human: running 5 m/s, no hit",Vec3(0,0,0),Vec3(0,0,5),none,8,true,30), true, 0);
  check(run(dw,*hum,"human: troll punch (fly)",Vec3(0,0,0),Vec3(),fly,10,true,30), true, 0);
  check(run(dw,*hum,"human: dropped from 4 m",Vec3(0,400,0),Vec3(),none,10,true,30), true, 0);
  check(run(dw,*hum,"human: 20 fps",Vec3(0,0,0),Vec3(),front,8,true,20), true, +1);
  check(run(dw,*hum,"human: 60 fps",Vec3(0,0,0),Vec3(),front,8,true,60), true, +1);
  check(run(dw,*hum,"human: unconscious",Vec3(0,0,0),Vec3(),front,8,true,30,Ragdoll::S_Unconscious), true, +1);
  check(run(dw,*wlf,"wolf: hit side",Vec3(0,0,0),Vec3(),side,8,false,30), false, 0);
  check(run(dw,*wlf,"wolf: running 8 m/s",Vec3(0,0,0),Vec3(0,0,8),none,8,false,30), false, 0);
  waterLevel = 300;
  auto rw = run(dw,*hum,"human: in deep water (slow sink)",Vec3(0,200,0),Vec3(),none,3,true,30);
  std::printf("   pelvis after 3 s in water: %.0f cm (без воды упал бы на землю)\n",double(rw.pelvisY));
  waterLevel = -1e9f;

  // нагрузка: 8 тел падают одновременно (друг на друга), затем удар по застывшему телу
  {
    std::vector<std::unique_ptr<Ragdoll>> rs;
    for(int i=0;i<8;++i) {
      std::vector<Matrix4x4> bones(hum->tr.size());
      Matrix4x4 obj = Matrix4x4::mkIdentity();
      obj.translate(float(i%3)*40.f, float(i/3)*60.f, float(i%2)*30.f);
      obj.rotateOY(float(i)*45.f);
      for(size_t k=0;k<bones.size();++k) bones[k] = obj*hum->tr[k];
      auto r = Ragdoll::create(dw,*hum,bones.data(),bones.size(),Ragdoll::S_Dead);
      if(r==nullptr)
        continue; // в физике нет места: это тело упало бы анимацией игры
      Ragdoll::Hit h; h.at = org(bones[12]); h.dir = Vec3(1,0,0); h.power = 3.f;
      r->hit(h);
      rs.push_back(std::move(r));
      }
    std::printf("8 bodies at once: %d got ragdoll (budget %d), the rest - animation\n", int(rs.size()), int(Ragdoll::MaxSimulated));
    if(rs.size()!=Ragdoll::MaxSimulated) fail++;
    double worst = 0, total = 0; int frames = 0; size_t maxSim = 0;
    for(int f=0; f<300; ++f) {
      auto t0 = std::chrono::steady_clock::now();
      dw.tick(33);
      double ms = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
      worst = std::max(worst,ms); total += ms; frames++;
      maxSim = std::max(maxSim, dw.ragdollsSimulated());
      }
    int sim = 0; for(auto& r:rs) sim += r->isSimulated();
    std::printf("8 bodies pile: avg %.2f ms/frame, worst %.2f ms (x86, 30 fps), at once: %d (max %d), still simulated: %d\n",
                total/frames, worst, int(maxSim), int(Ragdoll::MaxSimulated), sim);
    if(maxSim>Ragdoll::MaxSimulated) { fail++; std::printf("   ^^^ FAIL: budget\n"); }
    Ragdoll::Hit h; h.at = rs[0]->chestPosition(); h.dir = Vec3(0,0,1); h.power = 4.f;
    rs[0]->hit(h);
    std::printf("hit frozen corpse: simulated=%d\n", int(rs[0]->isSimulated()));
    for(int f=0; f<200; ++f) dw.tick(33);
    std::printf("after 6.6 s: simulated=%d pelvis.y=%.0f\n", int(rs[0]->isSimulated()), double(rs[0]->rootPosition().y));
    if(sim!=0 || rs[0]->isSimulated()) fail++;
  }

  // бюджет: место новому телу освобождает дальнее уже упавшее; падающее ещё - не трогается; герой - сверх бюджета
  {
    auto mk = [&](float x, float dist, bool keep) {
      std::vector<Matrix4x4> bones(hum->tr.size());
      Matrix4x4 obj = Matrix4x4::mkIdentity();
      obj.translate(x,0,300);
      for(size_t k=0;k<bones.size();++k) bones[k] = obj*hum->tr[k];
      return Ragdoll::create(dw,*hum,bones.data(),bones.size(),Ragdoll::S_Dead,dist,keep);
      };
    std::vector<std::unique_ptr<Ragdoll>> rs;
    for(int i=0;i<4;++i)
      rs.push_back(mk(float(i)*150.f, float(i)*500.f, false));
    auto early = mk(700, 100, false); // все ещё падают - места нет
    for(int f=0; f<30; ++f) dw.tick(33);
    auto nearOne = mk(800, 200, false);   // освобождает место: застывает дальнее (1500 см)
    auto farOne  = mk(950, 4000, false);  // дальше всех - анимацией
    auto hero    = mk(1100, 0, true);     // сверх бюджета
    const bool ok = early==nullptr && nearOne!=nullptr && farOne==nullptr && hero!=nullptr &&
                    rs[0]->isSimulated() && rs[1]->isSimulated() && rs[2]->isSimulated() && !rs[3]->isSimulated() &&
                    dw.ragdollsSimulated()==Ragdoll::MaxSimulated+1;
    std::printf("budget: falling ones kept, far settled one frozen for a near one, far new one - animation, hero extra: %s\n",
                ok ? "yes" : "no ^^^ FAIL");
    if(!ok) fail++;
    for(int f=0; f<120; ++f) dw.tick(33);
  }

  // живой падает с обрыва (S_Fall): не застывает по сроку мёртвых, удар о землю - со скоростью падения
  {
    auto r = spawn(dw,*hum,Vec3(0,600,-400),0,Ragdoll::S_Fall,Vec3(),0);
    float impact = 0, t = 0;
    for(; t<4.f; t+=0.033f) {
      dw.tick(33);
      impact = std::max(impact, r->takeImpact());
      }
    const float expect = std::sqrt(2.f*DynamicWorld::gravityMS*(6.f+0.85f)); // таз - на 95 см, лёг на 10 см
    const bool  ok     = r->isSimulated() && impact>0.8f*expect && impact<1.15f*expect;
    std::printf("fall 6 m alive: impact %.1f m/s (free fall %.1f), simulated at 4 s: %d %s\n",
                double(impact), double(expect), int(r->isSimulated()), ok ? "" : "^^^ FAIL");
    if(!ok) fail++;
    for(int f=0; f<200 && r->isSimulated(); ++f) dw.tick(33);
    if(r->isSimulated()) { fail++; std::printf("   ^^^ FAIL: alive ragdoll never expires\n"); }
    // в воду - без удара
    waterLevel = 200;
    auto w = spawn(dw,*hum,Vec3(300,600,-400),0,Ragdoll::S_Fall,Vec3(),0);
    float wi = 0;
    for(int f=0; f<90; ++f) { dw.tick(33); wi = std::max(wi, w->takeImpact()); }
    waterLevel = -1e9f;
    std::printf("fall 6 m into water: impact %.1f m/s %s\n", double(wi), wi==0 ? "" : "^^^ FAIL");
    if(wi!=0) fail++;
  }

  // поворот матрица -> кватернион -> матрица (Pose::toSample против mkMatrix)
  float maxErr = 0;
  for(int i=0;i<200;++i) {
    zenkit::AnimationSample s;
    float a = float(i)*0.37f, b = float(i)*0.11f, c = float(i)*0.53f;
    zenkit::Quat q(std::cos(a), std::sin(a)*std::cos(b), std::sin(a)*std::sin(b)*std::cos(c), std::sin(a)*std::sin(b)*std::sin(c));
    s.rotation = q; s.position = {float(i),2.f,3.f};
    Matrix4x4 m = mkMatrix(s);
    Matrix4x4 m2 = mkMatrix(toSampleCopy(m));
    for(int x=0;x<4;++x) for(int y=0;y<4;++y) maxErr = std::max(maxErr,std::abs(m.at(x,y)-m2.at(x,y)));
    }
  std::printf("matrix<->sample max error: %g\n",double(maxErr));
  if(maxErr>1e-4f) fail++;

  fail += activeTests(dw,*hum,*wlf);

  std::printf(fail==0 ? "ALL OK\n" : "FAILED: %d\n",fail);
  return fail;
  }

// копия toSample из pose.cpp
static zenkit::AnimationSample toSampleCopy(const Matrix4x4& m) {
  zenkit::AnimationSample s = {};
  float sx = Vec3(m.at(0,0),m.at(0,1),m.at(0,2)).length();
  float sy = Vec3(m.at(1,0),m.at(1,1),m.at(1,2)).length();
  float sz = Vec3(m.at(2,0),m.at(2,1),m.at(2,2)).length();
  sx = sx>0 ? sx : 1.f; sy = sy>0 ? sy : 1.f; sz = sz>0 ? sz : 1.f;
  auto r = [&](int i, int j) { const float k = (i==0 ? sx : (i==1 ? sy : sz)); return m.at(i,j)/k; };
  const float tr = r(0,0)+r(1,1)+r(2,2);
  float x=0, y=0, z=0, w=1;
  if(tr>0.f) { const float q = std::sqrt(tr+1.f)*2.f; w = 0.25f*q; x = (r(2,1)-r(1,2))/q; y = (r(0,2)-r(2,0))/q; z = (r(1,0)-r(0,1))/q; }
  else if(r(0,0)>r(1,1) && r(0,0)>r(2,2)) { const float q = std::sqrt(1.f+r(0,0)-r(1,1)-r(2,2))*2.f; w = (r(2,1)-r(1,2))/q; x = 0.25f*q; y = (r(0,1)+r(1,0))/q; z = (r(0,2)+r(2,0))/q; }
  else if(r(1,1)>r(2,2)) { const float q = std::sqrt(1.f+r(1,1)-r(0,0)-r(2,2))*2.f; w = (r(0,2)-r(2,0))/q; x = (r(0,1)+r(1,0))/q; y = 0.25f*q; z = (r(1,2)+r(2,1))/q; }
  else { const float q = std::sqrt(1.f+r(2,2)-r(0,0)-r(1,1))*2.f; w = (r(1,0)-r(0,1))/q; x = (r(0,2)+r(2,0))/q; y = (r(1,2)+r(2,1))/q; z = 0.25f*q; }
  s.rotation = zenkit::Quat(w,x,y,z);
  s.position.x = m.at(3,0); s.position.y = m.at(3,1); s.position.z = m.at(3,2);
  return s;
  }
