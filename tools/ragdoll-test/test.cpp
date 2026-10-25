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

int main() {
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
    bool ok = r.ok && r.frozenAt>0 && r.maxSep<6.f && r.pelvisY<40.f && r.pelvisY>-5.f;
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
      rs.push_back(Ragdoll::create(dw,*hum,bones.data(),bones.size(),Ragdoll::S_Dead));
      Ragdoll::Hit h; h.at = org(bones[12]); h.dir = Vec3(1,0,0); h.power = 3.f;
      rs.back()->hit(h);
      }
    double worst = 0, total = 0; int frames = 0;
    for(int f=0; f<300; ++f) {
      auto t0 = std::chrono::steady_clock::now();
      dw.tick(33);
      double ms = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
      worst = std::max(worst,ms); total += ms; frames++;
      }
    int sim = 0; for(auto& r:rs) sim += r->isSimulated();
    std::printf("8 bodies pile: avg %.2f ms/frame, worst %.2f ms (x86, 30 fps), still simulated: %d\n", total/frames, worst, sim);
    Ragdoll::Hit h; h.at = rs[0]->chestPosition(); h.dir = Vec3(0,0,1); h.power = 4.f;
    rs[0]->hit(h);
    std::printf("hit frozen corpse: simulated=%d\n", int(rs[0]->isSimulated()));
    for(int f=0; f<200; ++f) dw.tick(33);
    std::printf("after 6.6 s: simulated=%d pelvis.y=%.0f\n", int(rs[0]->isSimulated()), double(rs[0]->rootPosition().y));
    if(sim!=0 || rs[0]->isSimulated()) fail++;
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
