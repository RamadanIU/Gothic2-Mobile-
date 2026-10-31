// Gothic II Mobile: стенд регдолла (tools/ragdoll-test/build.sh).
// Проверка регдолла на компьютере: человек (Biped, T-поза) и волк; удары, бег, падение с высоты, вода.
#include "physics/ragdoll.h"
#include <cstdlib>
#include "physics/dynamicworld.h"
#include "graphics/mesh/skeleton.h"
#include "graphics/mesh/animmath.h"
#include "game/rangedaim.h"
#include "actor.h"

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

// Отклонение выведенной позы от анимации (корпус, руки, голова), см
static float shownDeviation(const bench::Actor& a) {
  float dev = 0;
  for(size_t i=0;i<a.disp.size();++i) {
    const std::string& nm = a.sk.nodes[i].name;
    if(nm.rfind("ZS_",0)==0 || nm.find("FINGER")!=std::string::npos) continue;
    dev = std::max(dev,(org(a.disp[i])-org(a.anim[i])).length());
    }
  return dev;
  }

static Vec3 bonePos(const bench::Actor& a, const char* name) {
  return org(a.disp[bench::bone(a.sk,name)]);
  }

// Стрела, как в игре: летит из точки в 15 м спереди (+Z) в aim, попадание и место - Ragdoll::rayTest по телу на экране;
// урон - по месту (RangedAim::zoneMul). Мимо - false
static bool shootArrow(bench::Actor& a, Vec3 aim, float power, float damage, Ragdoll::RayHit* out = nullptr) {
  const Vec3 from = aim + Vec3(0,20,1500);
  const Vec3 dir  = Vec3::normalize(aim-from);
  Ragdoll::RayHit h;
  if(!Ragdoll::rayTest(a.sk,a.disp.data(),a.disp.size(),from,aim+dir*200.f,h))
    return false;
  a.hit(h.at,dir,power,damage*RangedAim::zoneMul(h.zone),Ragdoll::H_Missile);
  if(out!=nullptr)
    *out = h;
  return true;
  }

// Тряска: наибольшая скорость частей корпуса, головы и рук на экране относительно таза, м/с (бег и шаги не в счёт)
struct Shake {
  std::vector<Vec3> prev, prevAnim;
  float             maxV = 0, maxAnim = 0, at = 0;
  const char*       worst = "";
  void tick(const bench::Actor& a) {
    const size_t pel = bench::bone(a.sk,"BIP01 PELVIS");
    std::vector<Vec3> cur, anim;
    for(auto nm:{"BIP01 SPINE2","BIP01 HEAD","BIP01 L HAND","BIP01 R HAND","BIP01 L FOREARM","BIP01 R FOREARM"}) {
      const size_t b = bench::bone(a.sk,nm);
      cur .push_back(org(a.disp[b])-org(a.disp[pel]));
      anim.push_back(org(a.anim[b])-org(a.anim[pel]));
      }
    static const char* names[] = {"spine2","head","l.hand","r.hand","l.forearm","r.forearm"};
    const float dt = float(a.dt)/1000.f;
    if(prev.size()==cur.size())
      for(size_t i=0;i<cur.size();++i) {
        const float v = (cur[i]-prev[i]).length()*0.01f/dt;
        if(v>maxV) { maxV = v; worst = names[i]; at = a.time; }
        maxAnim = std::max(maxAnim,(anim[i]-prevAnim[i]).length()*0.01f/dt);
        }
    prev = cur; prevAnim = anim;
    }
  };

// Сцены активного регдолла (ragtest vis <сцена> out.txt): тело на экране по кадрам для render.py
extern std::vector<std::pair<Vec3,Vec3>> benchBoxes;

// итог сцены толчка или подъёма (balanceTests)
struct SceneResult {
  float fellAt      = -1;  // с: не устоял (S_Fall)
  float recoveredAt = -1;  // с: устоял или встал - поза в анимации
  int   steps       = 0;   // шагов, ловя равновесие
  bool  gripped     = false;
  float getUpErr    = 0;   // наибольшее отставание тела от кадра подъёма, см
  float gatherErr   = 0;   // собрался лёжа: насколько тело (таз, грудь, голова, кисти, ступни) не в позе начала подъёма, см
  float holdRise    = 0;   // пока не начал вставать: наибольшая скорость головы и груди вверх, м/с («тянут за голову»)
  float pelvisY     = 0;   // таз на последнем кадре, см
  Vec3  pelvis      = {};
  double physMs     = 0;   // среднее время шага мира с куклой, мс
  int   physFrames  = 0;
  float selfPen     = 0;   // кукла в физике: насколько предплечья и кисти входили в свой корпус и голову, см
  float otherPen    = 0;   // насколько части тела входили в тело другого человека рядом, см
  float shoulderOver= 0;   // насколько плечо выходило за анатомический предел, град (кукла не на ногах)
  float jointOver   = 0;   // то же - любой сустав
  float behind      = 0;   // кисть за спиной: насколько дальше плоскости спины (грудь - 12 см назад), см
  bool  riseFailed  = false; // подъём телом не вышел (Ragdoll::riseFailed)
  float behindT     = 0;   // когда
  };

// Глубина, на которую капсулы a входят в капсулы b (наибольшая), см; pick - какие пары считать
template<class F>
static float capsulePen(const std::vector<Ragdoll::Obstacle>& a, const std::vector<Ragdoll::Obstacle>& b, F pick) {
  float pen = 0;
  for(auto& x:a)
    for(auto& y:b) {
      if(!pick(x,y))
        continue;
      // расстояние между отрезками - по точкам (проще, чем точная формула; шаг 1/16 длины)
      float d = 1e9f;
      for(int i=0;i<=16;++i) {
        const Vec3 p = x.a + (x.b-x.a)*(float(i)/16.f);
        const Vec3 ab = y.b-y.a;
        const float l2 = Vec3::dotProduct(ab,ab);
        const float t  = l2>1e-6f ? std::clamp(Vec3::dotProduct(p-y.a,ab)/l2,0.f,1.f) : 0.f;
        d = std::min(d,(p-(y.a+ab*t)).length());
        }
      pen = std::max(pen, x.r+y.r-d);
      }
  return pen;
  }

static int visScene(const std::string& name, const char* out, Shake* shake = nullptr, float* peakT = nullptr,
                    SceneResult* res = nullptr) {
  zenkit::Mesh mesh;
  // стена за спиной (толчок спереди - валится к ней): центр и половины размеров, см
  benchBoxes.clear();
  if(name=="shove_wall")
    benchBoxes.push_back({Vec3(0,110,-100), Vec3(160,110,10)});
  DynamicWorld dw(nullptr,mesh);
  benchBoxes.clear();
  auto hum = mkSkeleton(human(),"HUMANS.MDH");
  FILE* f = out!=nullptr ? std::fopen(out,"w") : nullptr;
  if(f==nullptr && out!=nullptr)
    return 1;
  bench::Actor a(dw,*hum,Vec3(0,0,0));
  float  len   = 3.f;
  std::vector<std::pair<float,std::function<void()>>> ev;
  auto chest = [&]() { return bonePos(a,"BIP01 SPINE2")+Vec3(0,0,12); };
  if(name=="chest") {
    ev.push_back({0.3f,[&]{ a.hit(chest(),Vec3(0,0,-1),3.f,0.12f); }});
    }
  else if(name=="shoulder") {
    ev.push_back({0.3f,[&]{ a.hit(bonePos(a,"BIP01 L UPPERARM")+Vec3(-4,-3,8),Vec3(0,-0.1f,-1),3.2f,0.2f,Ragdoll::H_Missile); }});
    }
  else if(name=="leg") {
    len = 4.f;
    ev.push_back({0.3f,[&]{ a.hit((bonePos(a,"BIP01 L THIGH")+bonePos(a,"BIP01 L CALF"))*0.5f+Vec3(0,0,8),Vec3(0.3f,0,-1),3.5f,0.3f); }});
    }
  else if(name=="head") {
    ev.push_back({0.3f,[&]{ a.hit(bonePos(a,"BIP01 HEAD")+Vec3(0,8,10),Vec3(0,0.1f,-1),3.5f,0.15f); }});
    }
  else if(name=="stagger") {
    len = 3.5f;
    ev.push_back({0.3f,[&]{ a.hit(chest(),Vec3(0,0,-1),4.8f,0.3f); }});
    }
  else if(name=="side") {
    len = 3.5f;
    ev.push_back({0.3f,[&]{ a.hit(bonePos(a,"BIP01 R UPPERARM")+Vec3(-8,-5,0),Vec3(1,0,0),4.5f,0.25f); }});
    }
  else if(name=="knock") {
    len = 5.f;
    ev.push_back({0.3f,[&]{ a.hit(chest(),Vec3(0,0,-1),6.5f,0.45f); }});
    }
  else if(name=="series") {
    len = 3.5f;
    ev.push_back({0.3f,[&]{ a.hit(chest(),Vec3(0,0,-1),3.2f,0.1f); }});
    ev.push_back({0.9f,[&]{ a.hit(chest()+Vec3(10,0,0),Vec3(-0.3f,0,-1),3.2f,0.1f); }});
    ev.push_back({1.5f,[&]{ a.hit(chest()-Vec3(10,0,0),Vec3(0.3f,0,-1),3.6f,0.15f); }});
    }
  else if(name=="death") {
    len = 4.f;
    ev.push_back({0.3f,[&]{ a.hit(chest(),Vec3(0,0,-1),4.f,0.3f); }});
    ev.push_back({0.4f,[&]{ a.r->setState(Ragdoll::S_Dead); a.r->startSag(); }});
    }
  else if(name=="limp") {
    len = 4.f;
    a.walking = true; a.speed = 110;
    a.inj.leg = 0; a.inj.limp = 0.9f;
    }
  else if(name=="arm") {
    // удар приходится в лёгкое предплечье (в бою оружие часто бьёт по рукам)
    ev.push_back({0.3f,[&]{ a.hit(bonePos(a,"BIP01 R FOREARM")+Vec3(0,0,6),Vec3(0,0,-1),3.5f,0.12f); }});
    }
  else if(name=="stumble") {
    // удар в грудь и анимация игры «отшатнуться» одновременно, как в бою
    ev.push_back({0.3f,[&]{ a.hit(chest(),Vec3(0,0,-1),3.2f,0.12f); a.stumbleAt = a.time; }});
    }
  else if(name=="fight" || name=="fight2h") {
    // драка, как в игре: меч в правой (свободна левая), удар за ударом; точка удара - как Npc::meleeHitPoint
    // (высота кисти нападающего на теле со стороны нападающего, вбок по замаху)
    len = 5.f;
    a.fight = true; a.freeArms = 1;
    const bool  big = (name=="fight2h");
    const float pw  = big ? 4.2f : 3.f;
    struct Blow { float t, handY, lat, dmg; };
    static const Blow blows[] = {{0.3f,130,10,0.14f}, {1.1f,112,-14,0.12f}, {1.9f,140,0,0.16f}, {2.7f,104,12,0.13f}};
    for(auto& bl:blows) {
      ev.push_back({bl.t,[&a,bl,pw,big]{
        const float h = 180.f, r = 0.11f*h;
        const Vec3  me = a.at;
        const float y  = std::clamp(bl.handY, me.y+0.4f*h, me.y+h);
        const Vec3  at = Vec3(me.x, y, me.z) + Vec3(0,0,1)*r + Vec3(-1,0,0)*std::clamp(bl.lat*0.5f,-r,r);
        a.hit(at, Vec3(0,0,-1), pw, big ? bl.dmg*1.8f : bl.dmg);
        }});
      }
    }
  // толчок с разбега (герой плечом или руками): тело ловит равновесие шагами, руками, опорой; не устоял - падает.
  // SPD - скорость центра масс после толчка, м/с (по умолчанию - своя у сцены)
  else if(name.rfind("shove",0)==0) {
    len = 4.f;
    const float spd = std::getenv("SPD") ? float(std::atof(std::getenv("SPD"))) : 0.f;
    auto pick = [spd](float def) { return spd>0.f ? spd : def; };
    if(name=="shove_front")
      ev.push_back({0.3f,[&,pick]{ a.shove(chest(),Vec3(0,0,-1),pick(1.8f)); }});
    else if(name=="shove_back")
      ev.push_back({0.3f,[&,pick]{ a.shove(chest()-Vec3(0,0,24),Vec3(0,0,1),pick(2.2f)); }});
    else if(name=="shove_side")
      ev.push_back({0.3f,[&,pick]{ a.shove(bonePos(a,"BIP01 R UPPERARM")+Vec3(-6,-4,0),Vec3(1,0,0),pick(1.6f)); }});
    else if(name=="shove_light")
      ev.push_back({0.3f,[&,pick]{ a.shove(chest(),Vec3(0,0,-1),pick(0.9f)); }});
    else if(name=="shove_hard")
      ev.push_back({0.3f,[&,pick]{ a.shove(chest(),Vec3(0,0,-1),pick(3.f)); }});
    else if(name=="shove_wall")
      ev.push_back({0.3f,[&,pick]{ a.shove(chest(),Vec3(0,0,-1),pick(2.2f)); }});
    else if(name=="shove_npc")
      ev.push_back({0.3f,[&,pick]{ a.shove(chest()+Vec3(-8,0,0),Vec3(0.45f,0,-1),pick(2.f)); }});
    else if(name=="shove_into")
      ev.push_back({0.3f,[&,pick]{ a.shove(chest(),Vec3(0,0,-1),pick(2.2f)); }});
    else {
      if(f!=nullptr) std::fclose(f);
      std::printf("unknown scene %s\n",name.c_str());
      return 1;
      }
    }
  // встаёт телом (S_GetUp): сбит с ног, лежит, встаёт - на спине и на животе; getup_anim - как раньше (поза куклы
  // переходит в анимацию подъёма)
  // подъём телом без анимации (Ragdoll::startRise): сбит с ног на спину или на живот, лежит, встаёт
  else if(name=="rise_back" || name=="rise_belly") {
    len = 6.5f;
    const bool belly = (name=="rise_belly");
    ev.push_back({0.1f,[&,belly]{
      if(belly)
        a.fall(bonePos(a,"BIP01 SPINE2")-Vec3(0,0,12),Vec3(0,0,1),5.f,Ragdoll::H_Fly,Vec3(0,0,2.5f)); else
        a.fall(Vec3(),Vec3(),0,Ragdoll::H_Melee,Vec3(0,0,-2.2f));
      }});
    ev.push_back({2.4f,[&]{ if(a.r) a.r->startRise(1.f); }});
    }
  else if(name=="getup_back" || name=="getup_belly" || name=="getup_anim") {
    len = 5.f;
    const bool belly = (name=="getup_belly");
    ev.push_back({0.2f,[&,belly]{
      if(belly)
        a.fall(chest()-Vec3(0,0,24),Vec3(0,0,1),3.5f,Ragdoll::H_Melee,Vec3(0,0,1.2f)); else
        a.fall(chest(),Vec3(0,0,-1),3.5f,Ragdoll::H_Melee,Vec3(0,0,-1.2f));
      }});
    ev.push_back({2.4f,[&,name]{ a.getUp(name!="getup_anim"); }});
    }
  // стойка героя перед толчком (слой позы, Npc::tickShove -> InjuryRig::applyBrace): бежит, за 0,35 с до человека -
  // плечо вперёд или руки к его груди, врезался - руки разгибаются, корпус пружинит
  else if(name=="brace_shoulder" || name=="brace_push1" || name=="brace_push2") {
    len = 2.2f;
    a.walking = true; a.speed = 360;
    a.inj.braceKind = name=="brace_shoulder" ? Injury::B_Shoulder : (name=="brace_push1" ? Injury::B_Push1 : Injury::B_Push2);
    a.inj.braceSide = 0;
    a.braceDemo = true;
    }
  else if(name=="walkhit") {
    len = 3.f;
    a.walking = true; a.speed = 110;
    ev.push_back({0.6f,[&]{ a.hit(chest(),Vec3(0,0,-1),3.5f,0.15f); }});
    }
  // гибель и падения (тряпичная кукла): как в игре - убит стоя ударом спереди, сзади, сбоку, в голову, на бегу,
  // после ударов (активная кукла оседает), стрелой; оглушён; удар тролля; сбит с ног; падение с высоты
  else if(name=="die_front") {
    len = 3.f;
    ev.push_back({0.2f,[&]{ a.kill(chest(),Vec3(0,0,-1),3.2f); }});
    }
  else if(name=="die_armcross") {
    // убит ударом по предплечью сбоку: рука летит поперёк груди - упирается в неё, а не проходит насквозь
    len = 3.f;
    ev.push_back({0.2f,[&]{ a.kill((bonePos(a,"BIP01 R FOREARM")+bonePos(a,"BIP01 R HAND"))*0.5f,Vec3(1.f,1.2f,0.3f),8.f); }});
    }
  else if(name=="die_npc") {
    // убит ударом спереди, за спиной стоит человек: тело падает на него, а не сквозь
    len = 3.f;
    ev.push_back({0.2f,[&]{ a.kill(chest(),Vec3(0,0,-1),3.2f); }});
    }
  else if(name=="die_back") {
    len = 3.f;
    ev.push_back({0.2f,[&]{ a.kill(chest()-Vec3(0,0,24),Vec3(0,0,1),3.2f); }});
    }
  else if(name=="die_side") {
    len = 3.f;
    ev.push_back({0.2f,[&]{ a.kill(bonePos(a,"BIP01 R UPPERARM")+Vec3(-8,-5,0),Vec3(1,0,0),3.4f); }});
    }
  else if(name=="die_head") {
    len = 3.f;
    ev.push_back({0.2f,[&]{ a.kill(bonePos(a,"BIP01 HEAD")+Vec3(0,6,10),Vec3(0.2f,0,-1),3.5f); }});
    }
  else if(name=="die_run") {
    len = 3.f;
    a.walking = true; a.speed = 400;
    ev.push_back({0.4f,[&]{ a.kill(chest(),Vec3(0,0,-1),3.f,Ragdoll::H_Melee,Ragdoll::S_Dead,Vec3(0,0,4)); }});
    }
  else if(name=="die_hits") {
    len = 3.5f;
    ev.push_back({0.2f,[&]{ a.hit(chest(),Vec3(0,0,-1),3.2f,0.15f); }});
    ev.push_back({0.7f,[&]{ a.kill(chest()+Vec3(8,0,0),Vec3(-0.3f,0,-1),3.6f); }});
    }
  else if(name=="arrow_chest") {
    // стрела из лука (60 м/с) в грудь: отдача и пошатывание, руки без оружия - к ране
    len = 3.5f;
    ev.push_back({0.3f,[&]{ shootArrow(a,chest()+Vec3(6,0,0),RangedAim::BowImpact*1.1f,0.2f); }});
    }
  else if(name=="arrow_head") {
    ev.push_back({0.3f,[&]{ shootArrow(a,bonePos(a,"BIP01 HEAD")+Vec3(0,9,0),RangedAim::BowImpact*1.2f,0.2f); }});
    }
  else if(name=="arrow_leg") {
    len = 4.f;
    ev.push_back({0.3f,[&]{ shootArrow(a,(bonePos(a,"BIP01 L THIGH")+bonePos(a,"BIP01 L CALF"))*0.5f,RangedAim::BowImpact,0.3f); }});
    }
  else if(name=="arrow_bolt") {
    // сильный болт арбалета: шатает, но с ног не сбивает
    len = 3.5f;
    ev.push_back({0.3f,[&]{ shootArrow(a,chest(),RangedAim::CrossbowImpact*1.6f,0.6f); }});
    }
  else if(name=="arrows") {
    len = 4.f;
    ev.push_back({0.3f,[&]{ shootArrow(a,chest()+Vec3(8,0,0),RangedAim::BowImpact*1.1f,0.15f); }});
    ev.push_back({1.1f,[&]{ shootArrow(a,chest()+Vec3(-8,-10,0),RangedAim::BowImpact*1.1f,0.15f); }});
    ev.push_back({1.9f,[&]{ shootArrow(a,bonePos(a,"BIP01 R UPPERARM"),RangedAim::BowImpact*1.1f,0.15f); }});
    }
  else if(name=="die_arrow") {
    len = 3.f;
    ev.push_back({0.2f,[&]{ a.kill(chest()+Vec3(-6,4,0),Vec3(0,-0.1f,-1),3.f,Ragdoll::H_Missile); }});
    }
  // агония: убит ударом спереди (на спину) или сзади (лицом вниз), оглушён; TL - сила, TS - зерно (своя агония)
  else if(name=="throes" || name=="throes_belly" || name=="throes_ko") {
    len = 12.f;
    a.throesLife = std::getenv("TL") ? float(std::atof(std::getenv("TL"))) : 0.8f;
    a.throesRise = std::getenv("TR") ? std::atoi(std::getenv("TR"))!=0 : true;
    a.throesSeed = std::getenv("TS") ? uint32_t(std::atoi(std::getenv("TS"))) : 1u;
    if(name=="throes")
      ev.push_back({0.2f,[&]{ a.kill(chest(),Vec3(0,0,-1),3.2f); }});
    else if(name=="throes_belly")
      ev.push_back({0.2f,[&]{ a.kill(chest()-Vec3(0,0,24),Vec3(0,0,1),3.2f); }});
    else
      ev.push_back({0.2f,[&]{ a.kill(bonePos(a,"BIP01 HEAD")+Vec3(0,4,10),Vec3(0,0,-1),2.8f,Ragdoll::H_Melee,Ragdoll::S_Unconscious); }});
    }
  else if(name=="ko") {
    len = 3.5f;
    ev.push_back({0.2f,[&]{ a.kill(bonePos(a,"BIP01 HEAD")+Vec3(0,4,10),Vec3(0,0,-1),2.8f,Ragdoll::H_Melee,Ragdoll::S_Unconscious); }});
    }
  else if(name=="troll") {
    len = 3.f;
    // как в игре: полёт задаёт MoveAlgo::accessDamFly (скорость NPC), удар - сверху
    ev.push_back({0.2f,[&]{ a.fall(chest(),Vec3(0,0,-1),8.f,Ragdoll::H_Fly,Vec3(0,2.5f,-3)); }});
    }
  else if(name=="fallfwd") {
    // живой падает вперёд с уступа 2 м: руки к земле
    len = 2.5f;
    a.at.y = 200;
    a.mkAnim(); a.disp = a.anim;
    ev.push_back({0.1f,[&]{ a.fall(chest(),Vec3(0,0,1),2.f,Ragdoll::H_Melee,Vec3(0,0,3)); }});
    }
  else if(name=="drop") {
    len = 3.f;
    a.at.y = 400;
    a.mkAnim(); a.disp = a.anim;
    ev.push_back({0.1f,[&]{ a.fall(Vec3(),Vec3(),0,Ragdoll::H_Melee,Vec3(0,0,1.5f)); }});
    }
  else {
    if(f!=nullptr)
      std::fclose(f);
    std::printf("unknown scene %s\n",name.c_str());
    return 1;
    }
  // другой человек рядом (опора для руки): стоит слева сзади
  std::unique_ptr<bench::Actor> other;
  if(name=="shove_npc")
    other = std::make_unique<bench::Actor>(dw,*hum,Vec3(62,0,-45),180.f);
  // стоит за спиной (смотрит в спину): упор для тела, не опора (die_npc) и опора (shove_into)
  if(name=="die_npc")
    other = std::make_unique<bench::Actor>(dw,*hum,Vec3(8,0,-72),0.f);
  if(name=="shove_into")
    other = std::make_unique<bench::Actor>(dw,*hum,Vec3(6,0,-80),0.f);
  size_t next = 0;
  float  peakDev = 0;
  // DT - шаг времени мира за кадр, мс (как в слоумо: 3-8 мс вместо 33); кадры для render.py - по-прежнему раз в 33 мс
  const uint64_t stepMs = std::getenv("DT") ? uint64_t(std::max(1,std::atoi(std::getenv("DT")))) : 33u;
  a.dt = stepMs;
  int lastFrame = -1;
  for(float t=0; t<len; t+=float(stepMs)/1000.f) {
    while(next<ev.size() && ev[next].first<=t) { ev[next].second(); ++next; }
    const auto tick0 = std::chrono::steady_clock::now();
    if(other) {
      // за что можно схватиться: плечи, грудь, предплечья другого - на поверхности его тела (как Npc::ragdollGrips)
      a.grips.clear();
      uint32_t id = 1;
      std::vector<Vec3> pts;
      Ragdoll::gripPoints(*hum, other->disp.data(), other->disp.size(), bonePos(a,"BIP01 SPINE2"), pts);
      for(auto& p:pts) {
        Ragdoll::Grip g; g.at = p; g.id = id; a.grips.push_back(g);
        }
      other->tick();
      // тело другого - упор для куклы (как Npc::tickRagdollProxy)
      if(!std::getenv("NOPROXY")) {
        if(!other->proxy)
          other->proxy = Ragdoll::Proxy::create(dw,*hum,id,false);
        other->proxy->update(other->disp.data(),other->disp.size());
        }
      }
    a.tick();
    if(res!=nullptr) {
      if(a.r && a.r->isSimulated()) {
        res->physMs += std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-tick0).count();
        res->physFrames++;
        }
      if(a.r && a.r->state()==Ragdoll::S_Balance) {
        res->steps   = std::max(res->steps, a.r->balanceSteps());
        res->gripped = res->gripped || a.r->isGripping();
        }
      if(a.r && a.r->state()==Ragdoll::S_Fall && res->fellAt<0.f)
        res->fellAt = t;
      if(a.r && a.r->isSimulated() && a.r->state()!=Ragdoll::S_Active) {
        res->shoulderOver = std::max(res->shoulderOver, a.r->overLimit(true));
        {
        // спина - по корпусу: вверх от поясницы к шее, влево - от правого плеча к левому, вперёд - их произведение
        const Vec3 lu = bonePos(a,"BIP01 L UPPERARM"), ru = bonePos(a,"BIP01 R UPPERARM");
        const Vec3 up = Vec3::normalize(bonePos(a,"BIP01 NECK")-bonePos(a,"BIP01 SPINE"));
        const Vec3 fw = Vec3::normalize(Vec3::crossProduct(lu-ru,up));
        const Vec3 c  = bonePos(a,"BIP01 SPINE2");
        for(auto nm:{"BIP01 L HAND","BIP01 R HAND"}) {
          const float bh = -Vec3::dotProduct(bonePos(a,nm)-c,fw) - 12.f;
          if(bh>res->behind) {
            res->behind  = bh;
            res->behindT = t;
            }
          }
        }
        res->jointOver    = std::max(res->jointOver,    a.r->overLimit(false));
        std::vector<Ragdoll::Obstacle> own;
        Ragdoll::obstaclesOf(*hum, a.disp.data(), a.disp.size(), 0, false, own);
        auto nm = [&](const Ragdoll::Obstacle& o) -> const std::string& { return a.sk.nodes[o.bone].name; };
        auto arm   = [&](const Ragdoll::Obstacle& o) { return nm(o).find("FOREARM")!=std::string::npos || nm(o).find("HAND")!=std::string::npos; };
        auto trunk = [&](const Ragdoll::Obstacle& o) { return nm(o).find("SPINE")!=std::string::npos || nm(o).find("PELVIS")!=std::string::npos ||
                                                               nm(o).find("HEAD")!=std::string::npos  || nm(o).find("NECK")!=std::string::npos; };
        res->selfPen = std::max(res->selfPen, capsulePen(own,own,[&](auto& x, auto& y){ return arm(x) && trunk(y); }));
        if(other) {
          std::vector<Ragdoll::Obstacle> oth;
          Ragdoll::obstaclesOf(*hum, other->disp.data(), other->disp.size(), 1, false, oth);
          res->otherPen = std::max(res->otherPen, capsulePen(own,oth,[](auto&,auto&){ return true; }));
          }
        }
      if(a.r && a.r->state()==Ragdoll::S_GetUp)
        res->getUpErr = std::max(res->getUpErr, a.r->getUpError());
      if(a.getUpAt>=0.f && a.time-a.getUpAt<a.getUpHold) {
        static Vec3 prevH, prevC;
        const Vec3 h = bonePos(a,"BIP01 HEAD"), c = bonePos(a,"BIP01 SPINE2");
        if(a.time-a.getUpAt>0.04f) {
          const float dts = float(a.dt)/1000.f;
          res->holdRise = std::max(res->holdRise, std::max(h.y-prevH.y, c.y-prevC.y)*0.01f/dts);
          }
        prevH = h; prevC = c;
        }
      if(a.getUpAt>=0.f && !a.r && a.blendAt>=0.f && std::abs(a.time-a.blendAt)<1e-4f) {
        for(auto nm:{"BIP01 PELVIS","BIP01 SPINE2","BIP01 HEAD","BIP01 L HAND","BIP01 R HAND","BIP01 L FOOT","BIP01 R FOOT"}) {
          const size_t b = bench::bone(a.sk,nm);
          res->gatherErr = std::max(res->gatherErr, (org(a.blendFrom[b])-org(a.anim[b])).length());
          }
        }
      if(a.recovered && res->recoveredAt<0.f)
        res->recoveredAt = t;
      if(a.riseResult<0)
        res->riseFailed = true;
      res->pelvis  = bonePos(a,"BIP01 PELVIS");
      res->pelvisY = res->pelvis.y;
      }
    const int frame = int(t/0.033f+1e-4f);
    if(frame==lastFrame)
      continue;
    lastFrame = frame;
    a.marks.clear();
    if(a.r && a.r->state()==Ragdoll::S_Active && a.r->isSimulated()) {
      std::vector<Matrix4x4> rel; Stance st;
      a.r->activePose(rel,st);
      for(int k=0;k<2;++k) if(st.footW[k]>0.01f) a.marks.push_back({st.foot[k],'g'});
      }
    if(a.inj.arm>=0 && a.inj.reach>0.f) {
      Vec3 p = a.inj.local; a.disp[a.inj.bone].project(p);
      a.marks.push_back({p,'r'});
      }
    if(a.throesLife>=0.f && a.killBone<a.disp.size()) {
      Vec3 p = a.killLocal; a.disp[a.killBone].project(p);
      a.marks.push_back({p,'r'});
      }
    if(shake!=nullptr && t>=0.25f) {
      const float before = shake->maxV;
      shake->tick(a);
      if(std::getenv("SHAKE_ALL") && t>0.85f && t<1.15f) {
        const size_t pel = bench::bone(a.sk,"BIP01 PELVIS"), hd = bench::bone(a.sk,"BIP01 R HAND");
        const Vec3 d = org(a.disp[hd])-org(a.disp[pel]), an = org(a.anim[hd])-org(a.anim[pel]);
        if(a.r && a.r->isSimulated()) {
          std::vector<Matrix4x4> rel; Stance st; a.r->activePose(rel,st);
          size_t k = 0; for(size_t q=0;q<a.r->bones().size();++q) if(a.r->bones()[q]==hd) k = q;
          const Vec3 rh = org(rel[k]);
          const Vec3 pp = org(a.r->world()[0]);
          std::printf("      rel hand=(%.1f %.1f %.1f) physPelvis=(%.1f %.1f %.1f) stance w=%.0f shift=(%.1f %.1f %.1f) rot=%.3f feetW=%.2f\n",
            double(rh.x),double(rh.y),double(rh.z),double(pp.x),double(pp.y),double(pp.z),double(st.weight),double(st.shift.x),double(st.shift.y),double(st.shift.z),
            double(st.rot.length()),double(st.footW[0]));
          }
        const Vec3 pw = org(a.disp[pel]), hw = org(a.disp[hd]);
        std::printf("   t=%.2f disp=(%.1f %.1f %.1f) anim=(%.1f %.1f %.1f) reach=%.2f arm=%d pelvis=(%.1f %.1f %.1f) hand=(%.1f %.1f %.1f) at=(%.1f %.1f)\n",double(t),double(d.x),double(d.y),double(d.z),
                    double(an.x),double(an.y),double(an.z),double(a.inj.reach),int(a.inj.arm),
                    double(pw.x),double(pw.y),double(pw.z),double(hw.x),double(hw.y),double(hw.z),double(a.at.x),double(a.at.z));
        }
      if(std::getenv("SHAKE_TRACE") && shake->maxV>before)
        std::printf("   t=%.2f %s %.2f m/s show=%.2f\n",double(t),shake->worst,double(shake->maxV),a.r ? double(a.r->showWeight()) : 0.0);
      }
    if(peakT!=nullptr && t>=0.3f) {
      const float d = shownDeviation(a);
      if(d>peakDev) { peakDev = d; *peakT = t-0.3f; }
      }
    if(f==nullptr)
      continue;
    char label[96];
    std::snprintf(label,sizeof(label),"%s show=%.2f",name.c_str(),a.r ? double(a.r->showWeight()) : 0.0);
    if(a.r && a.r->state()==Ragdoll::S_Balance)
      std::snprintf(label,sizeof(label),"%s balance",name.c_str());
    else if(a.r && a.r->state()==Ragdoll::S_Fall)
      std::snprintf(label,sizeof(label),"%s fall",name.c_str());
    else if(a.r && a.r->isRising())
      std::snprintf(label,sizeof(label),"%s rise",name.c_str());
    else if(a.r && a.r->state()==Ragdoll::S_GetUp)
      std::snprintf(label,sizeof(label),"%s getup err=%.0f",name.c_str(),double(a.r->getUpError()));
    else if(a.getUpAt>=0.f)
      std::snprintf(label,sizeof(label),"%s getup (anim)",name.c_str());
    else if(a.recovered)
      std::snprintf(label,sizeof(label),"%s %s",name.c_str(),a.riseResult<0 ? "RISE FAILED" : "recovered");
    a.dump(f,label);
    if(other)
      other->dump(f,"",true);
    if(name=="shove_wall")
      std::fprintf(f,"B 0 0 -100 0 220 -100 10 w\n");
    }
  if(f!=nullptr)
    std::fclose(f);
  return 0;
  }

// Слой ран без рывков: рука к ране плавно (вес 0..1 мелкими шагами - кисть сдвигается понемногу), рана на голове,
// груди, бедре, голени, руке. Возвращает наибольший сдвиг кисти за шаг 0,02 веса, см
static float injuryJump(const char* boneName, Vec3 off, bool print) {
  zenkit::Mesh mesh;
  DynamicWorld dw(nullptr,mesh);
  auto hum = mkSkeleton(human(),"HUMANS.MDH");
  auto rig = InjuryRig::of(*hum);
  auto base = bench::stancePose(*hum);
  InjuryRig::Wound w;
  const Vec3 at = org(base[bench::bone(*hum,boneName)])+off;
  rig->woundAt(base.data(),base.size(),at,w);
  Injury inj;
  inj.arm = rig->reachArm(w,3); inj.bone = w.bone; inj.local = w.local; inj.normal = w.normal;
  const size_t hand = bench::bone(*hum, inj.arm==0 ? "BIP01 L HAND" : "BIP01 R HAND");
  const size_t head = bench::bone(*hum,"BIP01 HEAD");
  float jump = 0, jumpHead = 0;
  Vec3 prev, prevH;
  for(int k=0;k<=50;++k) {
    auto p = base;
    inj.reach = float(k)/50.f;
    rig->apply(inj,Matrix4x4::mkIdentity(),p.data(),p.size());
    if(k>0) { jump = std::max(jump,(org(p[hand])-prev).length()); jumpHead = std::max(jumpHead,(org(p[head])-prevH).length()); }
    prev = org(p[hand]); prevH = org(p[head]);
    }
  if(print)
    std::printf("injury reach to %-16s hand jump per 2%% %.1f cm, head %.1f cm\n",boneName,double(jump),double(jumpHead));
  return std::max(jump,jumpHead);
  }

// ragtest shake: скорость частей тела на экране относительно таза в сценах (тряска) и сравнение с анимацией
// Агония (Ragdoll::startThroes, как Npc::startRagdollThroes): убит или оглушён - метрики по кадрам
struct ThroesRun {
  float chestMax  = 0;    // наибольшая высота груди над землёй после падения, см
  float headMax   = 0;    // и головы
  float chestEnd  = 0;    // в конце, см
  float kneeMax   = 0;    // наибольшая высота колен, см
  float handWound = 1e9f; // ближе всего кисть к ране, см
  float frozenAt  = -1;   // с после удара: тело застыло (ушло из физики)
  float shake     = 0;    // м/с: наибольшая скорость кистей, головы, груди относительно таза после падения
  float stillMove = 0;    // см: оглушённый «без памяти» - наибольший сдвиг груди за первые 0,9 с после падения
  };

static ThroesRun throesRun(uint32_t seed, float life, bool front, Ragdoll::State st = Ragdoll::S_Dead, bool finish = false,
                           const char* dump = nullptr) {
  zenkit::Mesh mesh;
  DynamicWorld dw(nullptr,mesh);
  auto hum = mkSkeleton(human(),"HUMANS.MDH");
  bench::Actor a(dw,*hum,Vec3(0,0,0));
  a.throesLife = life;
  a.throesSeed = seed;
  ThroesRun res;
  Shake     sh;
  FILE*     f = dump ? std::fopen(dump,"w") : nullptr;
  const float killAt = 0.2f;
  float landAt = -1, stillRef = 0;
  for(float t=0; t<18.f; t+=0.033f) {
    if(t>=killAt && !a.r) {
      const Vec3 ch = bonePos(a,"BIP01 SPINE2");
      if(st==Ragdoll::S_Unconscious)
        a.kill(bonePos(a,"BIP01 HEAD")+Vec3(0,4,10),Vec3(0,0,-1),2.8f,Ragdoll::H_Melee,Ragdoll::S_Unconscious); else
      if(front)
        a.kill(ch+Vec3(0,0,12),Vec3(0,0,-1),3.2f); else
        a.kill(ch-Vec3(0,0,12),Vec3(0,0,1),3.2f);
      if(finish && a.r)
        a.r->stopThroes();
      }
    a.tick();
    if(f) { char l[64]; std::snprintf(l,sizeof(l),"throes seed=%u",seed); a.dump(f,l); }
    if(!a.r)
      continue;
    if(res.frozenAt<0 && !a.r->isSimulated())
      res.frozenAt = t-killAt;
    const float chest = bonePos(a,"BIP01 SPINE2").y;
    if(landAt<0 && t>killAt+0.5f && bonePos(a,"BIP01 PELVIS").y<22.f) {
      landAt   = t;
      stillRef = chest;
      }
    if(landAt<0)
      continue;
    res.chestMax = std::max(res.chestMax, chest);
    res.headMax  = std::max(res.headMax, bonePos(a,"BIP01 HEAD").y);
    res.chestEnd = chest;
    res.kneeMax  = std::max({res.kneeMax, bonePos(a,"BIP01 L CALF").y, bonePos(a,"BIP01 R CALF").y});
    if(a.killBone<a.disp.size() && t>landAt+0.5f) {
      Vec3 w = a.killLocal; a.disp[a.killBone].project(w);
      for(auto h:{"BIP01 L HAND","BIP01 R HAND"})
        res.handWound = std::min(res.handWound, (bonePos(a,h)-w).length());
      }
    if(t<landAt+0.9f)
      res.stillMove = std::max(res.stillMove, std::abs(chest-stillRef));
    if(t>landAt+0.3f && a.r->isSimulated()) {
      sh.tick(a);
      res.shake = sh.maxV;
      } else {
      sh.prev.clear();
      }
    }
  if(f)
    std::fclose(f);
  return res;
  }

static int throesTests() {
  int  fail = 0;
  auto ok = [&](bool v, const char* what) { if(!v) { fail++; std::printf("   ^^^ FAIL: %s\n",what); } };
  auto show = [](const char* what, const ThroesRun& r) {
    std::printf("throes: %-26s head max %3.0f, chest max %3.0f end %3.0f cm, knees %3.0f cm, hand to wound %5.1f cm, frozen %5.2f s, shake %.2f m/s\n",
                what,double(r.headMax),double(r.chestMax),double(r.chestEnd),double(r.kneeMax),double(r.handWound),double(r.frozenAt),double(r.shake));
    };
  // зёрна подобраны под план (журнал "[g2m] ragdoll: throes ..."): 1 - пытается встать, 4 - корчится, держась за рану
  {
    const auto r = throesRun(1,0.8f,true);
    show("back, tries to get up",r);
    ok(r.headMax>40.f,"back: sits up on the hands"); ok(r.chestEnd<17.f,"back: falls back");
    ok(r.kneeMax>25.f,"back: knees up"); ok(r.frozenAt>4.f && r.frozenAt<16.5f,"back: writhes, then freezes");
    ok(r.shake<4.f,"back: no jerks");
    }
  {
    const auto r = throesRun(1,0.8f,false);
    show("belly, tries to get up",r);
    ok(r.headMax>45.f,"belly: pushes up on the arms"); ok(r.chestEnd<17.f,"belly: collapses");
    ok(r.frozenAt>4.f && r.frozenAt<16.5f,"belly: freezes"); ok(r.shake<4.f,"belly: no jerks");
    }
  {
    const auto r = throesRun(4,0.8f,true);
    show("back, clutches the wound",r);
    ok(r.handWound<12.f,"clutch: hand on the wound"); ok(r.kneeMax>25.f,"clutch: knees drawn up");
    ok(r.headMax<35.f,"clutch: does not sit up"); ok(r.shake<3.f,"clutch: no jerks");
    }
  {
    const auto r = throesRun(3,0.05f,true);
    show("killed outright (twitch)",r);
    ok(r.frozenAt>0.f && r.frozenAt<5.f,"twitch: dies at once");
    }
  {
    const auto r = throesRun(4,0.8f,true,Ragdoll::S_Dead,true);
    show("finished off (no throes)",r);
    ok(r.frozenAt>0.f && r.frozenAt<=float(Ragdoll::LiveTime)/1000.f+0.2f,"finish: no throes");
    }
  {
    const auto r = throesRun(1,0.8f,true,Ragdoll::S_Unconscious);
    std::printf("throes: knocked out              still for 0.9 s after the fall: chest moved %.1f cm\n",double(r.stillMove));
    show("knocked out",r);
    ok(r.stillMove<3.f,"knocked out: lies senseless first"); ok(r.headMax>35.f,"knocked out: then tries to get up");
    }

  // дыхание: умирающий - часто, пока корчится, потом 2-5 вдохов и тишина; оглушённый - ровно, пока не встанет
  {
    Breathing b;
    b.start(Breathing::Dying,0,7);
    int   cyclesStruggle = 0, cyclesAfter = 0, lastN = -1;
    float maxIn = 0, lastGasp = 0;
    bool  bad = false;
    int64_t stopAt = -1;
    for(int64_t t=0; t<60000; t+=16) {
      const bool struggling = t<6000;
      b.tick(t,struggling);
      if(!b.active()) { stopAt = t; break; }
      const float in = b.inhale(t);
      bad |= (in<0.f || in>1.f);
      maxIn = std::max(maxIn,in);
      if(b.n!=lastN) { lastN = b.n; (struggling ? cyclesStruggle : cyclesAfter)++; lastGasp = b.gasp; }
      }
    std::printf("breath: dying - %d breaths while writhing, %d after, last gasp %.2f, stops at %.1f s\n",
                cyclesStruggle,cyclesAfter,double(lastGasp),double(stopAt)/1000.0);
    ok(!bad && maxIn>0.7f,"breath: inhale 0..1"); ok(cyclesStruggle>=3,"breath: fast while writhing");
    ok(cyclesAfter>=2 && cyclesAfter<=6 && lastGasp>=0.99f,"breath: a few fading breaths, the last one a gasp");
    ok(stopAt>6000 && stopAt<6000+15000,"breath: stops");
    Breathing u;
    u.start(Breathing::Steady,0,9);
    float um = 0;
    for(int64_t t=0; t<120000; t+=16) { u.tick(t,false); um = std::max(um,u.inhale(t)); }
    std::printf("breath: unconscious - %d breaths in 2 min, still breathing %d\n",u.n,int(u.active()));
    ok(u.active() && u.n>=20 && u.n<=40 && um>0.5f,"breath: unconscious breathes steadily");
    }
  // дыхание на выводе позы: грудь поднимается на 1-3 см, таз и кисти на месте
  {
    zenkit::Mesh mesh;
    DynamicWorld dw(nullptr,mesh);
    auto hum = mkSkeleton(human(),"HUMANS.MDH");
    std::vector<Matrix4x4> bones(hum->tr.size());
    Matrix4x4 obj = Matrix4x4::mkIdentity();
    obj.translate(0,15,0);
    obj.rotateOX(-90); // лежит на спине
    for(size_t k=0;k<bones.size();++k) bones[k] = obj*hum->tr[k];
    auto r = Ragdoll::create(dw,*hum,bones.data(),bones.size(),Ragdoll::S_Unconscious);
    r->freeze();
    auto base = r->world(), in = r->world(), gasp = r->world();
    r->breathe(in,1.f,0.f);
    r->breathe(gasp,1.f,1.f);
    float chestIn = 0, chestGasp = 0, still = 0;
    for(size_t i=0;i<base.size();++i) {
      const float d = (org(in[i])-org(base[i])).length();
      if(r->bones()[i]==r->chestBone()) { chestIn = d; chestGasp = (org(gasp[i])-org(base[i])).length(); }
      const std::string& nm = hum->nodes[r->bones()[i]].name;
      if(nm.find("HAND")!=std::string::npos || nm.find("PELVIS")!=std::string::npos || nm.find("CALF")!=std::string::npos)
        still = std::max(still,d);
      }
    std::printf("breath: pose - chest rises %.1f cm (gasp %.1f cm), pelvis, hands, legs move %.2f cm\n",
                double(chestIn),double(chestGasp),double(still));
    ok(chestIn>0.8f && chestIn<2.5f && chestGasp>chestIn && chestGasp<4.f,"breath: chest rises"); ok(still<0.01f,"breath: limbs stay");
    }
  // цена: одно тело в агонии (кукла в физике, мышцы, луч к земле) - мс на кадр, пока корчится
  {
    zenkit::Mesh mesh;
    DynamicWorld dw(nullptr,mesh);
    auto hum = mkSkeleton(human(),"HUMANS.MDH");
    bench::Actor a(dw,*hum,Vec3(0,0,0));
    a.throesLife = 0.9f;
    a.throesSeed = 1;
    double total = 0, worst = 0;
    int    frames = 0;
    for(float t=0; t<10.f; t+=0.033f) {
      if(t>=0.2f && !a.r) a.kill(bonePos(a,"BIP01 SPINE2")+Vec3(0,0,12),Vec3(0,0,-1),3.2f);
      auto t0 = std::chrono::steady_clock::now();
      a.tick();
      const double ms = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
      if(t>1.5f && a.r && a.r->isSimulated()) { total += ms; worst = std::max(worst,ms); frames++; }
      }
    std::printf("throes: one body writhing - %.3f ms/frame avg, worst %.3f ms (x86, 30 fps, with pose output)\n",
                frames>0 ? total/frames : 0.0, worst);
    }
  return fail;
  }

static int shakeReport() {
  for(auto nm:{"chest","arm","head","shoulder","stumble","series","stagger","leg","side","shove_light","shove_front","shove_side","shove_npc","getup_back"}) {
    Shake sh;
    float peak = 0;
    visScene(nm,nullptr,&sh,&peak);
    std::printf("%-9s shown %.2f m/s (%s at %.2f s; animation %.2f m/s), deviation peak at %.2f s\n",nm,double(sh.maxV),
                sh.worst,double(sh.at),double(sh.maxAnim),double(peak));
    }
  return 0;
  }

// Лук и арбалет (game/rangedaim.h): попадание по частям тела (Ragdoll::rayTest), грудь - точка автонаведения, круг
// прицела по умению, выстрел по параболе - в точку прицела
static int aimTests(const Skeleton& hum, const Skeleton& wlf) {
  int fail = 0;
  auto ok = [&](bool v, const char* what) { if(!v) { fail++; std::printf("   ^^^ FAIL: %s\n",what); } };

  // человек в стойке лицом к +Z; стрела летит спереди назад
  const auto w = bench::stancePose(hum);
  auto P = [&](const char* nm) { return org(w[bench::bone(hum,nm)]); };
  struct Case { const char* what; Vec3 at; bool hit; uint8_t zone; };
  const Case cases[] = {
    {"head",           P("BIP01 HEAD")+Vec3(0,9,0),                     true,  Ragdoll::Z_Head},
    {"chest",          P("BIP01 SPINE2"),                               true,  Ragdoll::Z_Body},
    {"belly",          P("BIP01 SPINE")+Vec3(0,5,0),                    true,  Ragdoll::Z_Body},
    {"thigh",          (P("BIP01 L THIGH")+P("BIP01 L CALF"))*0.5f,     true,  Ragdoll::Z_Legs},
    {"shin",           (P("BIP01 R CALF")+P("BIP01 R FOOT"))*0.5f,      true,  Ragdoll::Z_Legs},
    {"beside chest",   P("BIP01 SPINE2")+Vec3(60,0,0),                  false, 0},
    {"between knees",  (P("BIP01 L CALF")+P("BIP01 R CALF"))*0.5f,      false, 0},
    {"over the head",  P("BIP01 HEAD")+Vec3(0,40,0),                    false, 0},
    };
  for(auto& c:cases) {
    Ragdoll::RayHit h;
    const bool hit  = Ragdoll::rayTest(hum,w.data(),w.size(),c.at+Vec3(0,0,800),c.at-Vec3(0,0,800),h);
    const bool good = hit==c.hit && (!hit || (h.zone==c.zone && h.at.z>0.f && h.at.z<30.f));
    std::printf("aim: arrow to %-14s %s zone %d, enters at z=%.1f cm\n",c.what,hit ? "hits  " : "misses",int(h.zone),double(h.at.z));
    ok(good,c.what);
    }
  {
    Vec3 ch;
    const bool has = Ragdoll::chestPoint(hum,w.data(),w.size(),ch);
    std::printf("aim: chest point (%.0f %.0f %.0f)\n",double(ch.x),double(ch.y),double(ch.z));
    ok(has && ch.y>115.f && ch.y<150.f && std::abs(ch.x)<5.f,"chest point at the chest");
  }
  // волк боком (стрела вдоль X): передние и задние лапы - ноги, голова - голова, бок - тело
  {
    std::vector<Matrix4x4> ww(wlf.tr.begin(),wlf.tr.end());
    auto Q = [&](const char* nm) { return org(ww[bench::bone(wlf,nm)]); };
    const Case wc[] = {
      {"wolf front leg", (Q("BIP01 L FOREARM")+Q("BIP01 L HAND"))*0.5f, true, Ragdoll::Z_Legs},
      {"wolf hind leg",  (Q("BIP01 L THIGH")+Q("BIP01 L CALF"))*0.5f,   true, Ragdoll::Z_Legs},
      {"wolf head",      Q("BIP01 HEAD")+Vec3(0,0,8),                   true, Ragdoll::Z_Head},
      {"wolf side",      Q("BIP01 SPINE1"),                             true, Ragdoll::Z_Body},
      };
    for(auto& c:wc) {
      Ragdoll::RayHit h;
      const bool hit = Ragdoll::rayTest(wlf,ww.data(),ww.size(),c.at+Vec3(500,0,0),c.at-Vec3(500,0,0),h);
      std::printf("aim: arrow to %-14s %s zone %d\n",c.what,hit ? "hits  " : "misses",int(h.zone));
      ok(hit && h.zone==c.zone,c.what);
      }
  }
  // круг прицела: 100 % - точка, 10 % - SpreadAt10, уже с умением; разброс не выходит за круг
  {
    float prev = 1e9f;
    bool  mono = true;
    for(int sk=0; sk<=100; sk+=5) {
      const float r = RangedAim::spreadDeg(sk);
      mono &= r<=prev;
      prev  = r;
      }
    std::printf("aim: spread 10%% %.2f deg, 30%% %.2f, 60%% %.2f, 100%% %.2f\n",double(RangedAim::spreadDeg(10)),
                double(RangedAim::spreadDeg(30)),double(RangedAim::spreadDeg(60)),double(RangedAim::spreadDeg(100)));
    ok(mono && RangedAim::spreadDeg(100)==0.f && std::abs(RangedAim::spreadDeg(10)-RangedAim::SpreadAt10)<1e-4f,"spread by skill");
    const Vec3 from(0,150,0), at(300,180,2500);
    float worst = 0;
    for(int i=0; i<500; ++i) {
      const Vec3  p = RangedAim::scatter(from,at,4.f,float(i%25)/24.f,float(i/25)/20.f);
      const float a = std::acos(std::clamp(Vec3::dotProduct(Vec3::normalize(p-from),Vec3::normalize(at-from)),-1.f,1.f));
      worst = std::max(worst,a*180.f/float(M_PI));
      }
    std::printf("aim: scatter 4 deg - widest %.2f deg\n",double(worst));
    ok(worst<=4.01f && worst>3.9f,"scatter stays in the circle");
  }
  // выстрел по параболе (как DynamicWorld::moveBullet: шаг 33 мс) приходит в точку прицела
  {
    struct Shot { Vec3 to; float speed; };
    const Shot shots[] = {{Vec3(0,150,1500),RangedAim::BowSpeed},{Vec3(400,450,3000),RangedAim::BowSpeed},
                          {Vec3(-200,100,6000),RangedAim::CrossbowSpeed},{Vec3(0,-300,2500),RangedAim::BowSpeed}};
    for(auto& sh:shots) {
      const Vec3 from(0,150,0);
      Vec3  v    = RangedAim::launch(from,sh.to,sh.speed,DynamicWorld::gravity);
      Vec3  p    = from;
      float best = 1e9f;
      for(int k=0; k<400; ++k) {
        const float dt = 33.f;
        const Vec3  q  = p + v*dt - Vec3(0,0.5f*DynamicWorld::gravity*dt*dt,0);
        // ближайшая к цели точка шага (стрела проверяет отрезок шага)
        const Vec3  d  = q-p;
        const float t  = std::clamp(Vec3::dotProduct(sh.to-p,d)/std::max(Vec3::dotProduct(d,d),1e-6f),0.f,1.f);
        best = std::min(best,(p+d*t-sh.to).length());
        p    = q;
        v.y -= DynamicWorld::gravity*dt;
        }
      std::printf("aim: shot %2.0f m at %.0f m/s - off the aim point by %.1f cm\n",double((sh.to-from).length()/100.f),
                  double(sh.speed*10.f),double(best));
      ok(best<3.f,"shot lands on the aim point");
      }
  }
  // плавная смена цели (PlayerControl::tickRangedAim): прицел идёт по дуге от старой цели к новой - без рывка в начале и
  // в конце, всё время единичное направление, в конце - точно в новую цель; кадры по 33 мс
  {
    const Vec3 a = Vec3::normalize(Vec3(-0.6f,0.05f,1.f)), b = Vec3::normalize(Vec3(0.7f,-0.1f,1.f));
    const float total = std::acos(Vec3::dotProduct(a,b));
    float prev = 0, maxStep = 0, firstStep = -1, lastStep = 0, unit = 0;
    bool  mono = true;
    for(float t=0; t<=RangedAim::GlideMs+1.f; t+=33.f) {
      const Vec3  d  = RangedAim::slerpDir(a,b,RangedAim::glide(t/RangedAim::GlideMs));
      const float ang = std::acos(std::clamp(Vec3::dotProduct(a,d),-1.f,1.f));
      const float step = ang-prev;
      mono     = mono && step>=-1e-5f;
      maxStep  = std::max(maxStep,step);
      if(t>0 && firstStep<0)
        firstStep = step;
      lastStep = step;
      unit     = std::max(unit,std::abs(d.length()-1.f));
      prev     = ang;
      }
    const float endErr = std::acos(std::clamp(Vec3::dotProduct(RangedAim::slerpDir(a,b,RangedAim::glide(1.f)),b),-1.f,1.f));
    std::printf("aim: glide %.0f deg in %.0f ms - first step %.2f deg, widest %.2f, last %.2f, end off %.3f deg\n",
                double(total*180.f/float(M_PI)),double(RangedAim::GlideMs),double(firstStep*180.f/float(M_PI)),
                double(maxStep*180.f/float(M_PI)),double(lastStep*180.f/float(M_PI)),double(endErr*180.f/float(M_PI)));
    ok(mono && unit<1e-4f && endErr<1e-3f,"glide goes one way to the new target");
    ok(firstStep<0.25f*maxStep && lastStep<0.25f*maxStep,"glide starts and stops softly");
    // почти та же цель - без деления на ноль
    const Vec3 c = RangedAim::slerpDir(a,a,0.5f);
    ok(std::abs(c.length()-1.f)<1e-4f,"glide to the same direction");
  }
  return fail;
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
    std::printf("active: holds pose               max deviation %.1f cm, shown %.2f\n",double(dev),double(r->showWeight()));
    ok(dev<3.f,"holds pose"); ok(r->showWeight()==0.f,"no hits - animation on screen");
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
    ok(dev<5.f,"turns: relative pose");
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

  // 3) обычный удар в грудь (одноручный, 12 % здоровья): отклик заметный, НЕ мгновенный (пик не раньше 0,15 с),
  // тело возвращается в анимацию плавно, стоит, почти не сдвигается
  {
    bench::Actor a(dw,hum,Vec3(300,0,-600));
    for(int f=0; f<5; ++f) a.tick();
    a.hit(bonePos(a,"BIP01 SPINE2")+Vec3(0,0,12),Vec3(0,0,-1),3.f,0.12f);
    const Vec3 at0 = a.at;
    float peak = 0, peakT = 0, back = 0, shown = 0;
    for(int f=0; f<75; ++f) {
      a.tick();
      const float d = shownDeviation(a), t = float(f+1)*0.033f;
      if(d>peak) { peak = d; peakT = t; }
      shown = std::max(shown,a.r->showWeight());
      if(t>2.3f) back = std::max(back,d);
      }
    const float moved = (a.at-at0).length();
    std::printf("active: hit chest 3 m/s           reaction %.1f cm at %.2f s, after 2.3 s %.1f cm, moved %.0f cm, %s\n",
                double(peak),double(peakT),double(back),double(moved),a.r->lostBalance() ? "FELL" : "stands");
    ok(peak>6.f && peak<35.f,"hit: visible, not exaggerated"); ok(peakT>=0.3f,"hit: weighty, not instant");
    ok(back<1.5f,"hit: back to animation"); ok(!a.r->lostBalance(),"hit: stands"); ok(moved<30.f,"hit: barely moves");
    ok(shown==1.f && a.r->showWeight()==0.f,"hit: physics shown during the reaction only");
  }
  // 3б) без тряски: части тела на экране относительно таза не быстрее предела - удар в грудь, в лёгкое предплечье,
  // в голову, удар вместе с анимацией игры «отшатнуться», серия ударов, пошатывание (руки для равновесия)
  {
    struct { const char* scene; float limit; } lim[] = {
      {"chest",2.2f}, {"arm",2.2f}, {"head",2.6f}, {"stumble",2.f}, {"series",2.6f}, {"stagger",3.f},
      };
    for(auto& l:lim) {
      Shake sh;
      float peak = 0;
      visScene(l.scene,nullptr,&sh,&peak);
      std::printf("active: shake %-8s              %.2f m/s (%s), limit %.1f\n",l.scene,double(sh.maxV),sh.worst,double(l.limit));
      ok(sh.maxV<=l.limit,"shake: body parts move too fast");
      }
  }
  // 4) серия слабых ударов и удары разной силы спереди, сзади, сбоку: слабые не валят, сильные - валят
  auto knock = [&](float power, float dmg, Vec3 dir, float& moved, int& steps) {
    bench::Actor a(dw,hum,Vec3(600,0,-600));
    for(int f=0; f<5; ++f) a.tick();
    const Vec3 c = bonePos(a,"BIP01 SPINE2");
    a.hit(c-Vec3::normalize(dir)*12.f,dir,power,dmg);
    const Vec3 at0 = a.at;
    bool lost = false;
    for(int f=0; f<70 && !lost; ++f) { a.tick(); lost = a.r->state()==Ragdoll::S_Fall; }
    moved = (a.at-at0).length();
    steps = 0;
    return lost;
    };
  for(float pw : {2.5f,3.5f,4.5f,5.5f,7.f}) {
    float moved = 0; int steps = 0;
    const float dmg = pw<4.f ? 0.1f : (pw<6.f ? 0.3f : 0.5f);
    const bool lost = knock(pw,dmg,Vec3(0,0,-1),moved,steps);
    std::printf("active: hit %.1f m/s, %2.0f%% hp        %s, moved %.0f cm\n",double(pw),double(dmg*100.f),lost ? "falls" : "keeps balance",double(moved));
    if(pw<=4.5f) ok(!lost,"stagger: weak hit must not knock down");
    if(pw>=4.5f && pw<6.f) ok(moved>20.f,"stagger: strong hit pushes back with steps");
    if(pw>=7.f) ok(lost,"stagger: very strong hit must knock down");
    }
  for(Vec3 d : {Vec3(0,0,1),Vec3(1,0,0)}) {
    float m1 = 0, m2 = 0; int s1 = 0, s2 = 0;
    const bool weak = knock(3.f,0.1f,d,m1,s1), strong = knock(7.f,0.5f,d,m2,s2);
    std::printf("active: from %s       3 m/s %s (moved %.0f), 7 m/s %s\n", d.z>0 ? "behind" : "side  ",
                weak ? "falls" : "keeps", double(m1), strong ? "falls" : "keeps");
    ok(!weak,"stagger: weak hit from behind/side"); ok(strong,"stagger: strong hit from behind/side");
    }
  // 5) пошатнулся: ступни переступают (не скользят), потом стоит как в анимации
  {
    bench::Actor a(dw,hum,Vec3(900,0,-600));
    for(int f=0; f<5; ++f) a.tick();
    a.hit(bonePos(a,"BIP01 SPINE2")+Vec3(0,0,12),Vec3(0,0,-1),4.8f,0.3f);
    int   steps = 0;
    float slide = 0, lastFoot[2] = {0,0};
    bool  wasPlanted[2] = {false,false};
    Vec3  prevFoot[2];
    for(int f=0; f<90; ++f) {
      a.tick();
      std::vector<Matrix4x4> rel; Stance st;
      if(a.r->state()!=Ragdoll::S_Active) break;
      a.r->activePose(rel,st);
      for(int k=0;k<2;++k) {
        const Vec3 p = bonePos(a, k==0 ? "BIP01 L FOOT" : "BIP01 R FOOT");
        const bool planted = std::abs(p.y-lastFoot[k])<0.3f && st.footW[k]>0.99f;
        if(planted && wasPlanted[k]) slide = std::max(slide, Vec3(p.x-prevFoot[k].x,0,p.z-prevFoot[k].z).length());
        if(!planted && wasPlanted[k]) steps++;
        wasPlanted[k] = planted; lastFoot[k] = p.y; prevFoot[k] = p;
        }
      }
    const float dev = shownDeviation(a);
    std::printf("active: stagger 4.8 m/s           steps %d, planted foot slides %.1f cm, after 3 s deviation %.1f cm, %s\n",
                steps,double(slide),double(dev),a.r->state()==Ragdoll::S_Active ? "stands" : "FELL");
    ok(steps>=2,"stagger: steps"); ok(slide<2.f,"stagger: feet do not slide"); ok(dev<2.f,"stagger: back to animation");
  }
  // 6) удар в ногу: она подгибается (таз ниже), рука тянется к ране
  {
    bench::Actor a(dw,hum,Vec3(-900,0,-600));
    for(int f=0; f<5; ++f) a.tick();
    const float pel0 = bonePos(a,"BIP01 PELVIS").y;
    const Vec3  w    = (bonePos(a,"BIP01 L THIGH")+bonePos(a,"BIP01 L CALF"))*0.5f+Vec3(0,0,8);
    a.hit(w,Vec3(0.3f,0,-1),3.5f,0.3f);
    float drop = 0, reach = 1e9f;
    for(int f=0; f<60; ++f) {
      a.tick();
      drop = std::max(drop, pel0-bonePos(a,"BIP01 PELVIS").y);
      if(a.inj.arm>=0) {
        Vec3 p = a.inj.local; a.disp[a.inj.bone].project(p);
        const Vec3 hand = bonePos(a, a.inj.arm==0 ? "BIP01 L HAND" : "BIP01 R HAND");
        reach = std::min(reach,(hand-p).length());
        }
      }
    std::printf("active: hit leg                   pelvis drops %.1f cm, hand to wound %.1f cm, limp %.2f\n",double(drop),double(reach),double(a.inj.limp));
    ok(drop>4.f,"leg: buckles"); ok(reach<16.f,"leg: hand reaches the wound"); ok(a.inj.leg==0 && a.inj.limp>0.3f,"leg: limps");
  }
  // 7) стрела в плечо: корпус поворачивается
  {
    bench::Actor a(dw,hum,Vec3(-1200,0,-600));
    for(int f=0; f<5; ++f) a.tick();
    auto yaw = [&]() { const Vec3 d = bonePos(a,"BIP01 L UPPERARM")-bonePos(a,"BIP01 R UPPERARM"); return std::atan2(d.z,d.x)*180.f/float(M_PI); };
    const float y0 = yaw();
    a.hit(bonePos(a,"BIP01 L UPPERARM")+Vec3(-4,-3,8),Vec3(0,-0.1f,-1),3.2f,0.2f,Ragdoll::H_Missile);
    float twist = 0;
    for(int f=0; f<30; ++f) { a.tick(); twist = std::max(twist,std::abs(yaw()-y0)); }
    std::printf("active: arrow in shoulder         torso turns %.0f deg\n",double(twist));
    ok(twist>8.f,"arrow: torso twists");
  }
  // 7б) стрела из лука в грудь (место - по телу, как в игре): тело шатается шагами, рука без оружия - к ране, стоит
  {
    bench::Actor a(dw,hum,Vec3(-2100,0,-600));
    for(int f=0; f<5; ++f) a.tick();
    const Vec3 p0 = a.at;
    Ragdoll::RayHit h;
    const bool hit = shootArrow(a,bonePos(a,"BIP01 SPINE2")+Vec3(6,0,0),RangedAim::BowImpact*1.1f,0.2f,&h);
    bool  stag = false, lost = false;
    float reach = 1e9f;
    for(int f=0; f<60; ++f) {
      a.tick();
      stag |= a.r && a.r->isStaggering();
      lost |= a.r && a.r->state()!=Ragdoll::S_Active;
      if(a.inj.arm>=0) {
        Vec3 p = a.inj.local; a.disp[a.inj.bone].project(p);
        reach = std::min(reach,(bonePos(a, a.inj.arm==0 ? "BIP01 L HAND" : "BIP01 R HAND")-p).length());
        }
      }
    const float moved = (a.at-p0).length();
    std::printf("active: arrow in chest            %s, staggers %d, moved %.0f cm, hand to wound %.1f cm, %s\n",
                hit ? "hits" : "misses",int(stag),double(moved),double(reach),lost ? "falls" : "stands");
    ok(hit && h.zone==Ragdoll::Z_Body,"arrow: hits the chest"); ok(stag && moved>4.f,"arrow: body staggers");
    ok(reach<16.f,"arrow: hand reaches the wound"); ok(!lost,"arrow: keeps balance");
  }
  // 7в) сильный болт арбалета: шатает, но одна стрела с ног не сбивает
  {
    bench::Actor a(dw,hum,Vec3(-2400,0,-600));
    for(int f=0; f<5; ++f) a.tick();
    shootArrow(a,bonePos(a,"BIP01 SPINE2"),RangedAim::CrossbowImpact*1.6f,0.6f);
    bool lost = false;
    for(int f=0; f<70; ++f) { a.tick(); lost |= a.r && a.r->state()!=Ragdoll::S_Active; }
    std::printf("active: crossbow bolt 60%% hp       %s\n",lost ? "falls" : "stands");
    ok(!lost,"bolt: one missile does not knock down");
  }
  // 8) убит стоя: оседает (не падает сразу), потом лежит и застывает
  {
    bench::Actor a(dw,hum,Vec3(-1500,0,-600));
    for(int f=0; f<5; ++f) a.tick();
    a.hit(bonePos(a,"BIP01 SPINE2")+Vec3(0,0,12),Vec3(0,0,-1),4.f,0.3f);
    a.tick();
    a.r->setState(Ragdoll::S_Dead);
    a.r->startSag();
    float at25 = 0;
    for(int f=0; f<130 && a.r->isSimulated(); ++f) {
      a.tick();
      if(f==7) at25 = a.r->rootPosition().y;
      }
    std::printf("active -> dead standing: pelvis at 0.25 s %.0f cm, at the end %.0f cm, simulated %d\n",
                double(at25),double(a.r->rootPosition().y),int(a.r->isSimulated()));
    ok(at25>55.f,"death: sags, not instant collapse"); ok(a.r->rootPosition().y<40.f && !a.r->isSimulated(),"death: lies and freezes");
  }
  // 9) сбит с ног: падает, лежит (корчится), без NaN
  {
    bench::Actor a(dw,hum,Vec3(1200,0,-600));
    for(int f=0; f<5; ++f) a.tick();
    a.hit(bonePos(a,"BIP01 SPINE2")+Vec3(0,0,12),Vec3(0,0,-1),7.f,0.5f);
    bool finite = true; float impact = 0;
    for(int f=0; f<120; ++f) {
      a.tick();
      impact = std::max(impact,a.r->takeImpact());
      for(auto& m:a.r->world()) { Vec3 o = org(m); finite &= std::isfinite(o.x) && std::isfinite(o.y) && std::isfinite(o.z); }
      }
    std::printf("knocked down: pelvis %.0f cm, chestFront.y %+.2f, impact %.1f m/s, resting %d\n",
                double(a.r->rootPosition().y),double(a.r->chestFront().y),double(impact),int(a.r->isResting()));
    ok(finite,"fall: finite"); ok(a.r->state()==Ragdoll::S_Fall && a.r->rootPosition().y<40.f,"fall: lies on ground");
  }
  // 10) живой падает вперёд с 2 м: руки к земле до удара; с 15 м - машет руками
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
    // первые ~0,5 с группируется, потом машет руками - смотрим 1,65 с полёта (с 15 м он длится 1,75 с); размах -
    // наибольший угол между направлениями плеча (относительно таза) за полёт
    std::vector<Vec3> dirs; float turn = 0;
    for(int f=0; f<50; ++f) {
      r->setTarget(pose,33); dw.tick(33);
      for(size_t k=0;k<r->bones().size();++k) {
        const std::string nm = hum.nodes[r->bones()[k]].name;
        if(nm!="BIP01 L UPPERARM") continue;
        Matrix4x4 ip = r->world()[0]; ip.inverse();
        Vec3 d = Vec3::normalize(col(ip*r->world()[k],0));
        for(auto& o:dirs)
          turn = std::max(turn, std::acos(std::clamp(Vec3::dotProduct(o,d),-1.f,1.f))*180.f/float(M_PI));
        dirs.push_back(d);
        }
      }
    std::printf("fall 15 m: arm swings %.0f deg in the air\n",double(turn));
    ok(turn>90.f,"fall: flails in a long fall");
    for(int f=0; f<200 && r->isSimulated(); ++f) { r->setTarget(pose,33); dw.tick(33); }
  }
  // 11) убитый с позой анимации: оседает и ложится (мышцы слабеют)
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
  // 12) волк на ногах: лапы держатся, удар сбоку - тело откликается и возвращается
  {
    Matrix4x4 obj = objAt(Vec3(-300,0,-900));
    auto pose = poseAt(wlf,obj);
    auto r = Ragdoll::create(dw,wlf,pose.data(),pose.size(),Ragdoll::S_Active);
    r->setTarget(pose,0);
    for(int f=0; f<10; ++f) { r->setTarget(pose,33); dw.tick(33); }
    Ragdoll::Hit h; h.at = Vec3(-300+5,63,-900); h.dir = Vec3(-1,0,0); h.power = 3.f; h.damage = 0.15f;
    r->hitActive(h,true);
    float peak = 0, back = 0;
    for(int f=0; f<75; ++f) {
      r->setTarget(pose,33); dw.tick(33);
      const float d = relDeviation(*r,pose);
      if(f<20) peak = std::max(peak,d);
      if(f>=66) back = std::max(back,d);
      }
    std::printf("active wolf: hit side            reaction %.1f cm, after 2.2 s %.1f cm\n",double(peak),double(back));
    ok(peak>4.f && back<2.5f,"wolf: reacts and returns");
  }
  // 13) хромает: на больную ногу таз проседает заметно сильнее, чем на здоровую
  {
    bench::Actor a(dw,hum,Vec3(0,0,900));
    a.walking = true; a.speed = 110;
    bench::Actor b(dw,hum,Vec3(300,0,900));
    b.walking = true; b.speed = 110;
    a.inj.leg = 0; a.inj.limp = 0.9f;
    float minA = 1e9f, minB = 1e9f, maxA = -1e9f, maxB = -1e9f;
    for(int f=0; f<66; ++f) {
      a.tick(); b.tick();
      const float pa = bonePos(a,"BIP01 PELVIS").y, pb = bonePos(b,"BIP01 PELVIS").y;
      minA = std::min(minA,pa); maxA = std::max(maxA,pa); minB = std::min(minB,pb); maxB = std::max(maxB,pb);
      }
    std::printf("limp: pelvis bob %.1f cm (healthy %.1f cm)\n",double(maxA-minA),double(maxB-minB));
    ok(maxA-minA > maxB-minB+3.f,"limp: pelvis dips on the injured leg");
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
        if(f%15==i) { Ragdoll::Hit h; h.at = org(pose[12]); h.dir = Vec3(0,0,-1); h.power = 3.f; rs[size_t(i)]->hitActive(h,false); }
        }
      auto t0 = std::chrono::steady_clock::now();
      dw.tick(33);
      double ms = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
      total += ms; worst = std::max(worst,ms);
      }
    std::printf("%-30s %5.2f ms/frame avg, worst %5.2f ms\n", title, total/90.0, worst);
    };
  if(std::getenv("ACTIVE_ONLY")) {
    benchActive("4 alive on feet (active)", 4);
    return 0;
    }
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

// Толчок с разбега (S_Balance) и подъём телом (S_GetUp): устоял или упал - по направлению и силе толчка; стена и
// человек рядом держат; встаёт, не отставая от кадра подъёма; то же при шаге мира 4 мс (слоумо)
// Столкновения тела: руки не уходят в свой корпус (сквозь сустав «плечо» предплечье с грудью раньше не сталкивалось),
// тело не проходит сквозь человека рядом (живые NPC в физике - не тела: упор Ragdoll::setObstacles)
// Суставы: насколько плечо и прочие выходят за анатомию в падениях, агонии, равновесии (без расширения пределов -
// поза анимации на ногах может быть шире, но не упавшее тело)
static int limitReport() {
  const char* list[] = {"die_front","die_back","die_side","die_head","die_run","die_hits","die_arrow","ko","troll","drop",
                        "fallfwd","throes","throes_belly","throes_ko","shove_front","shove_back","shove_side","shove_hard",
                        "shove_wall","shove_npc","getup_back","getup_belly"};
  int fail = 0;
  for(auto nm:list) {
    SceneResult r;
    visScene(nm,nullptr,nullptr,nullptr,&r);
    std::printf("limits: %-13s shoulder over the limit %5.1f deg, any joint %5.1f deg, hand behind the back %5.1f cm at %.2f s\n",
                nm,double(r.shoulderOver),double(r.jointOver),double(r.behind),double(r.behindT));
    }
  return fail;
  }

static int contactTests() {
  int fail = 0;
  struct L { const char* scene; float self, other; };
  // пределы, см: капсулы - приближение, мягкий упор и решатель оставляют немного
  const L list[] = {
    {"die_front",4,0}, {"die_back",4,0}, {"die_side",4,0}, {"die_hits",4,0}, {"die_run",4,0}, {"ko",4,0},
    {"fight",4,0}, {"throes",4,0}, {"throes_belly",4,0}, {"fallfwd",4,0}, {"shove_front",4,0}, {"shove_side",4,0},
    {"die_npc",4,4}, {"shove_into",4,3}, {"shove_npc",4,3},
    };
  for(auto& l:list) {
    SceneResult r;
    visScene(l.scene,nullptr,nullptr,nullptr,&r);
    const bool good = r.selfPen<=l.self && (l.other<=0.f || r.otherPen<=l.other);
    std::printf("contact: %-13s arms into own body %4.1f cm, into the other person %4.1f cm%s\n",l.scene,double(r.selfPen),
                double(r.otherPen),good ? "" : " ^^^ FAIL");
    if(!good)
      fail++;
    }
  return fail;
  }

static int balanceTests() {
  int fail = 0;
  auto scene = [](const char* name, float spd, int dt = 0, float steady = 1.f) {
    SceneResult r;
    if(spd>0.f) setenv("SPD",std::to_string(spd).c_str(),1); else unsetenv("SPD");
    if(dt>0) setenv("DT",std::to_string(dt).c_str(),1); else unsetenv("DT");
    setenv("STEADY",std::to_string(steady).c_str(),1);
    visScene(name,nullptr,nullptr,nullptr,&r);
    unsetenv("SPD");
    unsetenv("DT");
    unsetenv("STEADY");
    return r;
    };
  auto expect = [&](const char* title, const SceneResult& r, bool keep, int minSteps, bool grip) {
    const bool kept = r.recoveredAt>0.f && r.fellAt<0.f;
    bool ok = keep ? kept : (r.fellAt>0.f);
    ok = ok && r.steps>=minSteps && (!grip || r.gripped);
    std::printf("shove: %-34s %s at %.2f s, steps %d%s, %.2f ms/frame %s\n", title, kept ? "kept balance" : (r.fellAt>0.f ? "fell" : "still"),
                double(kept ? r.recoveredAt : r.fellAt), r.steps, r.gripped ? ", gripped" : "",
                r.physFrames>0 ? r.physMs/r.physFrames : 0.0, ok ? "" : "^^^ FAIL");
    if(!ok) fail++;
    };
  // Человек борется (пользователь: «один шаг и падает - нелепо»): толчок с разбега (~1,8-2,2 м/с) - несколько шагов
  // назад, вбок через ногу, вперёд, руки машут - и обычно устоит; сильный - борется шагами и падает; у каждого NPC
  // своя устойчивость (Shove::steady): слабый на ногах падает там, где крепкий устоит
  expect("light, from the front (0.9 m/s)",  scene("shove_light",0),       true,  1, false);
  expect("from the front 1.2 m/s",           scene("shove_front",1.2f),    true,  1, false);
  expect("from the front 1.8 m/s",           scene("shove_front",1.8f),    true,  2, false);
  expect("from the front 2.2 m/s",           scene("shove_front",2.2f),    true,  3, false);
  expect("from the front 3.0 m/s",           scene("shove_front",3.0f),    false, 3, false);
  expect("front 2.6 m/s, unsteady (0.8)",    scene("shove_front",2.6f,0,0.8f), false, 3, false);
  expect("front 2.6 m/s, steady (1.2)",      scene("shove_front",2.6f,0,1.2f), true,  3, false);
  expect("from behind 1.5 m/s",              scene("shove_back",1.5f),     true,  1, false);
  expect("from behind 2.2 m/s",              scene("shove_back",2.2f),     true,  2, false);
  expect("from behind 3.0 m/s",              scene("shove_back",3.0f),     false, 1, false);
  expect("from the side 0.8 m/s",            scene("shove_side",0.8f),     true,  1, false);
  expect("from the side 1.8 m/s",            scene("shove_side",1.8f),     true,  3, false);
  expect("from the side 2.2 m/s",            scene("shove_side",2.2f),     false, 3, false);
  expect("into a wall 2.2 m/s",              scene("shove_wall",2.2f),     true,  0, true);
  expect("toward a person 1.5 m/s",          scene("shove_npc",1.5f),      true,  1, true);
  expect("front 1.2 m/s, world step 4 ms",   scene("shove_front",1.2f,4),  true,  1, false);
  expect("front 1.8 m/s, world step 4 ms",   scene("shove_front",1.8f,4),  true,  2, false);
  expect("front 3.0 m/s, world step 4 ms",   scene("shove_front",3.0f,4),  false, 2, false);
  {
    // у стены: тело остаётся у стены, а не проходит сквозь неё
    const auto r = scene("shove_wall",2.6f);
    const bool ok = r.pelvis.z>-92.f;
    std::printf("shove: into a wall 2.6 m/s - pelvis at z %.0f (wall face -90) %s\n", double(r.pelvis.z), ok ? "" : "^^^ FAIL");
    if(!ok) fail++;
  }
  // встаёт: собрался лёжа почти в позу начала подъёма (переход позы - короткий, по земле), пока лежит - ничто не
  // «всплывает» (голова и грудь не идут вверх раньше анимации), в конце стоит
  for(auto nm:{"getup_back","getup_belly","getup_anim"}) {
    const auto r = scene(nm,0);
    const bool phys = std::string(nm)!="getup_anim";
    const bool ok = r.fellAt>0.f && r.recoveredAt>0.f && r.pelvisY>80.f && r.holdRise<0.45f && (!phys || r.gatherErr<30.f);
    std::printf("getup: %-12s fell at %.2f, stood at %.2f, gathered within %.0f cm, rises before the animation %.2f m/s, pelvis at the end %.0f cm %s\n",
                nm, double(r.fellAt), double(r.recoveredAt), double(r.gatherErr), double(r.holdRise), double(r.pelvisY), ok ? "" : "^^^ FAIL");
    if(!ok) fail++;
    }
  {
    const auto r = scene("getup_back",0,4);
    const bool ok = r.recoveredAt>0.f && r.pelvisY>80.f && r.holdRise<0.45f && r.gatherErr<30.f;
    std::printf("getup: back, world step 4 ms - gathered within %.0f cm, rises before the animation %.2f m/s %s\n",
                double(r.gatherErr), double(r.holdRise), ok ? "" : "^^^ FAIL");
    if(!ok) fail++;
  }
  return fail;
  }

// Сводка «устоял/упал» по направлению и силе толчка: ./ragtest sweep - шагов, когда устоял или упал
static int balanceSweep() {
  std::string out;
  for(auto nm:{"shove_front","shove_back","shove_side"}) {
    char buf[64];
    std::snprintf(buf,sizeof(buf),"%-12s",nm);
    out += buf;
    for(float spd:{1.0f,1.4f,1.8f,2.2f,2.6f,3.0f}) {
      SceneResult r;
      setenv("SPD",std::to_string(spd).c_str(),1);
      visScene(nm,nullptr,nullptr,nullptr,&r);
      unsetenv("SPD");
      const bool kept = r.recoveredAt>0.f && r.fellAt<0.f;
      std::snprintf(buf,sizeof(buf)," %.1f:%s %dst %.1fs |", double(spd), kept ? "kept" : (r.fellAt>0.f ? "FELL" : "still"), r.steps,
                    double(kept ? r.recoveredAt-0.3f : r.fellAt-0.3f));
      out += buf;
      }
    out += "\n";
    }
  std::printf("sweep (m/s: устоял/упал, шагов, через сколько с после толчка)\n%s", out.c_str());
  return 0;
  }


// ---- Бой телом (game/fightbody.h): два человека, ходы по «часам», клинок - касанием
#include "fight.h"

struct ClockSeg {
  float             at;
  FightBody::Move   move;
  int               stages;
  float             stageLen, strikeAt;
  };

static void driveClock(bench::Fighter& f, const std::vector<ClockSeg>& segs, float t) {
  FightBody::Clock c;
  for(size_t i=0; i<segs.size(); ++i) {
    const auto& s = segs[i];
    const float total = float(s.stages)*s.stageLen;
    if(t<s.at || t>=s.at+total)
      continue;
    c.move  = s.move;
    c.t     = t-s.at;
    c.total = total;
    c.stage = std::min(int(c.t/s.stageLen), s.stages-1);
    c.stageFrom = float(c.stage)*s.stageLen;
    c.stageTo   = c.stageFrom+s.stageLen;
    c.strike    = s.strikeAt>0.f ? c.stageFrom+s.strikeAt : -1.f;
    c.swap      = s.strikeAt;
    c.id        = uint32_t(i*16+size_t(c.stage)+1);
    }
  f.clock = c;
  }

struct DuelResult {
  int   hitsA = 0, blocksA = 0, hitsB = 0, blocksB = 0;
  bool  fell  = false;          // кто-то упал (бой без падений - ни один удар не должен валить)
  float lagMax = 0;             // наибольшее отставание кисти куклы от цели в окне удара, см
  };

static int duelScene(const std::string& name, const char* out, DuelResult* res = nullptr) {
  zenkit::Mesh mesh;
  benchBoxes.clear();
  DynamicWorld dw(nullptr,mesh);
  auto hum = mkSkeleton(human(),"HUMANS.MDH");
  FILE* f = out!=nullptr ? std::fopen(out,"w") : nullptr;
  float dist = 165;
  bench::Fighter A(dw,*hum,Vec3(0,0,0),0.f);
  bench::Fighter B(dw,*hum,Vec3(0,0,dist),180.f);
  B.a.stepWorld = false;
  B.a.id = 101;
  A.foe = &B; B.foe = &A;
  std::vector<ClockSeg> sa, sb;
  float len = 5.5f;
  if(name=="duel" || name=="duel2h" || name=="duelfist") {
    FightBody::Weapon w = FightBody::W_1H;
    float sl = 0.7f, st = 0.4f, bl = 90;
    if(name=="duel2h")  { w = FightBody::W_2H;   sl = 0.95f; st = 0.55f; bl = 125; }
    if(name=="duelfist"){ w = FightBody::W_Fist; sl = 0.5f;  st = 0.26f; bl = 0; dist = 110; B.a.at = Vec3(0,0,dist); }
    A.weapon = B.weapon = w; A.bladeLen = B.bladeLen = bl;
    sa = {{0.6f,FightBody::M_Attack,3,sl,st}, {3.4f+sl,FightBody::M_AttackL,1,sl+0.1f,st+0.05f}};
    sb = {{0.6f+sl+0.05f,FightBody::M_Parade,1,0.65f,-1}, {3.0f,FightBody::M_AttackR,1,sl+0.1f,st+0.05f}};
    }
  else if(name=="duelwalk") {
    dist = 330; B.a.at = Vec3(0,0,dist);
    sa = {{1.3f,FightBody::M_Attack,2,0.7f,0.4f}};
    }
  else if(name=="duelrun") {
    // разбег в бою: шаги ног, потом удар с разбега
    dist = 600; B.a.at = Vec3(0,0,dist);
    sa = {{1.25f,FightBody::M_AttackRun,1,0.8f,0.45f}};
    }
  else if(name=="draw") {
    A.foe = nullptr; B.foe = nullptr;
    B.a.at = Vec3(400,0,0);
    sa = {{0.4f,FightBody::M_Draw,1,1.0f,0.45f}, {2.4f,FightBody::M_Sheath,1,1.0f,0.55f}};
    len = 3.8f;
    }
  else {
    std::printf("unknown scene %s\n",name.c_str());
    return 1;
    }
  A.a.startActive(); A.a.r->setCombat(true);
  B.a.startActive(); B.a.r->setCombat(true);
  auto gh = [](FightBody::Weapon w) { return uint8_t(w==FightBody::W_Fist ? 0 : (w==FightBody::W_2H ? 3 : 2)); };
  A.a.r->setGripHands(gh(A.weapon));
  B.a.r->setGripHands(gh(B.weapon));
  if(name=="draw") { A.fb.reset(); }
  const uint64_t stepMs = std::getenv("DT") ? uint64_t(std::max(1,std::atoi(std::getenv("DT")))) : 33u;
  A.a.dt = B.a.dt = stepMs;
  int lastFrame = -1;
  for(float t=0; t<len; t+=float(stepMs)/1000.f) {
    driveClock(A,sa,t);
    driveClock(B,sb,t);
    if(name=="draw") {
      // в руке после мига смены
      }
    if(name=="duelwalk")
      A.vel = (t>0.3f && B.a.at.z-A.a.at.z>175.f) ? Vec3(0,0,140) : Vec3();
    if(name=="duelrun")
      A.vel = (t>0.2f && B.a.at.z-A.a.at.z>165.f) ? Vec3(0,0,std::min(380.f,(t-0.2f)*900.f)) : Vec3();
    const float dts = float(stepMs)/1000.f;
    A.advance(dts); B.advance(dts);
    A.a.tick(); B.a.tick();
    A.sweep(); B.sweep();
    if(res!=nullptr && ((A.a.r && A.a.r->state()==Ragdoll::S_Fall) || (B.a.r && B.a.r->state()==Ragdoll::S_Fall)))
      res->fell = true;
    const int frame = int(t/0.033f+1e-4f);
    if(frame==lastFrame || f==nullptr)
      continue;
    lastFrame = frame;
    char label[96];
    static const char* mv[] = {"guard","attack","attackL","attackR","run","finish","parade","jump","draw","sheath","hit"};
    std::snprintf(label,sizeof(label),"%s A:%s%s B:%s%s",name.c_str(),mv[A.clock.move],A.fb.striking()?"*":"",
                  mv[B.clock.move],B.fb.striking()?"*":"");
    A.a.marks.clear();
    for(auto& m:A.fx) A.a.marks.push_back(m);
    for(auto& m:B.fx) A.a.marks.push_back(m);
    A.a.dump(f,label);
    B.a.dump(f,"",true);
    A.dumpBlade(f,'s');
    B.dumpBlade(f,'q');
    }
  if(f) std::fclose(f);
  if(res!=nullptr) {
    res->hitsA = A.hits; res->blocksA = A.blocks; res->hitsB = B.hits; res->blocksB = B.blocks;
    return 0;
    }
  for(auto& l:A.log) std::printf("A %s\n",l.c_str());
  for(auto& l:B.log) std::printf("B %s\n",l.c_str());
  std::printf("%s: A hits %d blocks %d, B hits %d blocks %d\n",name.c_str(),A.hits,A.blocks,B.hits,B.blocks);
  return 0;
  }

// Подъём телом без анимации: со спины и с живота, при обычном шаге мира и при слоумо (4 мс) - встаёт, не дольше
// 4,5 с, в конце таз на высоте стойки
static int riseTests() {
  int fail = 0;
  for(const char* nm:{"rise_back","rise_belly"}) {
    for(const char* dt:{"33","4"}) {
      setenv("DT",dt,1);
      SceneResult r;
      visScene(nm,nullptr,nullptr,nullptr,&r);
      const bool ok = r.recoveredAt>0.f && !r.riseFailed && r.recoveredAt-2.4f<4.5f && r.pelvisY>80.f;
      std::printf("rise: %-10s DT=%-2s %s at %.2f s, pelvis %.0f cm %s\n",nm,dt,r.riseFailed ? "FAILED" : (r.recoveredAt>0.f ? "stood" : "still lying"),
                  double(r.recoveredAt),double(r.pelvisY),ok ? "" : "<- BAD");
      if(!ok)
        fail++;
      }
    }
  unsetenv("DT");
  return fail;
  }

// Бой телом: удары доходят (клинок касается тела), блок ловит удар, никто не падает от обычных ударов
static int fightTests() {
  int fail = 0;
  for(const char* nm:{"duel","duel2h","duelfist","duelwalk","duelrun"}) {
    DuelResult r;
    duelScene(nm,nullptr,&r);
    const bool ok = r.hitsA+r.blocksA>=1 && (std::string(nm)!="duel" || r.hitsA>=1) && !r.fell;
    std::printf("fight: %-9s A hits %d blocks %d, B hits %d blocks %d%s %s\n",nm,r.hitsA,r.blocksA,r.hitsB,r.blocksB,
                r.fell ? ", someone FELL" : "",ok ? "" : "<- BAD");
    if(!ok)
      fail++;
    }
  return fail;
  }

int main(int argc, char** argv) {
  if(argc>3 && std::string(argv[1])=="vis" && (std::string(argv[2]).rfind("duel",0)==0 || std::string(argv[2])=="draw"))
    return duelScene(argv[2],argv[3]);
  if(argc>3 && std::string(argv[1])=="vis")
    return visScene(argv[2],argv[3]);
  if(argc>1 && std::string(argv[1])=="sweep")
    return balanceSweep();
  if(argc>1 && std::string(argv[1])=="shake")
    return shakeReport();
  if(argc>1 && std::string(argv[1])=="throes")
    return throesTests();
  if(argc>1 && std::string(argv[1])=="balance")
    return balanceTests();
  if(argc>1 && std::string(argv[1])=="contact")
    return contactTests();
  if(argc>1 && std::string(argv[1])=="limits")
    return limitReport();
  if(argc>1 && std::string(argv[1])=="rise")
    return riseTests();
  if(argc>1 && std::string(argv[1])=="fight")
    return fightTests();
  if(argc>1 && std::string(argv[1])=="injury") {
    injuryJump("BIP01 HEAD",Vec3(0,8,10),true);
    injuryJump("BIP01 SPINE2",Vec3(0,0,12),true);
    injuryJump("BIP01 L UPPERARM",Vec3(-4,-3,8),true);
    injuryJump("BIP01 L THIGH",Vec3(0,-20,8),true);
    injuryJump("BIP01 R CALF",Vec3(0,-15,8),true);
    injuryJump("BIP01 R FOREARM",Vec3(0,0,6),true);
    return 0;
    }
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

  fail += aimTests(*hum,*wlf);
  fail += activeTests(dw,*hum,*wlf);
  fail += throesTests();
  fail += balanceTests();
  fail += contactTests();
  fail += riseTests();
  fail += fightTests();

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
