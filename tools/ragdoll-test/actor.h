// Gothic II Mobile: стенд регдолла - «NPC» для активного регдолла (tools/ragdoll-test/build.sh).
// Поза анимации (стойка, ходьба), перемещение от пошатывания, слой ран (рука к ране, хромота), вывод позы - как
// Pose::implMkSkeletonActive (таз и ноги - анимация со стойкой, корпус и руки - физика относительно таза), кадры
// для render.py.
#pragma once

#include "physics/ragdoll.h"
#include "physics/dynamicworld.h"
#include "graphics/mesh/skeleton.h"
#include "graphics/mesh/injury.h"
#include "graphics/mesh/limbik.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <functional>
#include <memory>
#include <string>
#include <vector>

using namespace Tempest;

namespace bench {

inline Vec3 org(const Matrix4x4& m) { return Vec3(m.at(3,0),m.at(3,1),m.at(3,2)); }

inline Matrix4x4 objAt(Vec3 at, float rotY = 0) {
  Matrix4x4 m = Matrix4x4::mkIdentity();
  m.translate(at);
  m.rotateOY(rotY);
  return m;
  }

inline size_t bone(const Skeleton& sk, const char* name) {
  for(size_t i=0;i<sk.nodes.size();++i)
    if(sk.nodes[i].name==name)
      return i;
  return size_t(-1);
  }

inline bool isUnder(const Skeleton& sk, size_t i, size_t root) {
  for(size_t p=i; p<sk.nodes.size(); p=sk.nodes[p].parent)
    if(p==root)
      return true;
  return false;
  }

// повернуть кость и всех потомков так, чтобы кость смотрела на want (в системе, где заданы w)
inline void aim(const Skeleton& sk, std::vector<Matrix4x4>& w, const char* name, const char* childName, Vec3 want) {
  const size_t b = bone(sk,name), c = bone(sk,childName);
  if(b==size_t(-1) || c==size_t(-1))
    return;
  const Vec3 at  = org(w[b]);
  const Vec3 cur = org(w[c])-at;
  Matrix4x4 tmp = Matrix4x4::mkIdentity();
  LimbIk::rotateBasis(tmp,cur,want);
  for(size_t i=0;i<w.size();++i) {
    if(!isUnder(sk,i,b))
      continue;
    Matrix4x4 m = w[i];
    // поворот вокруг at тем же наименьшим поворотом
    const Vec3 o = org(m)-at;
    Matrix4x4 r = tmp; LimbIk::setOrigin(r,Vec3());
    Vec3 no = Vec3(r.at(0,0)*o.x + r.at(1,0)*o.y + r.at(2,0)*o.z,
                   r.at(0,1)*o.x + r.at(1,1)*o.y + r.at(2,1)*o.z,
                   r.at(0,2)*o.x + r.at(1,2)*o.y + r.at(2,2)*o.z);
    LimbIk::rotateBasis(m,cur,want);
    LimbIk::setOrigin(m,at+no);
    w[i] = m;
    }
  }

// Стойка с оружием: руки опущены и согнуты вперёд, колени чуть согнуты (система модели: вперёд +Z, влево +X)
inline std::vector<Matrix4x4> stancePose(const Skeleton& sk) {
  std::vector<Matrix4x4> w(sk.tr.begin(), sk.tr.end());
  aim(sk,w,"BIP01 L UPPERARM","BIP01 L FOREARM",Vec3( 0.25f,-0.93f, 0.2f));
  aim(sk,w,"BIP01 L FOREARM", "BIP01 L HAND",   Vec3( 0.05f,-0.45f, 0.9f));
  aim(sk,w,"BIP01 R UPPERARM","BIP01 R FOREARM",Vec3(-0.25f,-0.93f, 0.25f));
  aim(sk,w,"BIP01 R FOREARM", "BIP01 R HAND",   Vec3(-0.1f, -0.3f,  0.95f));
  aim(sk,w,"BIP01 L THIGH","BIP01 L CALF",Vec3( 0.06f,-1.f, 0.12f));
  aim(sk,w,"BIP01 L CALF", "BIP01 L FOOT",Vec3( 0.f,  -1.f,-0.1f));
  aim(sk,w,"BIP01 R THIGH","BIP01 R CALF",Vec3(-0.06f,-1.f, 0.05f));
  aim(sk,w,"BIP01 R CALF", "BIP01 R FOOT",Vec3( 0.f,  -1.f,-0.15f));
  // ступни - на землю (y=0): опустить всё тело
  const float fy = std::min(org(w[bone(sk,"BIP01 L FOOT")]).y, org(w[bone(sk,"BIP01 R FOOT")]).y) - 8.f;
  for(auto& m:w) LimbIk::setOrigin(m, org(m)-Vec3(0,fy,0));
  return w;
  }

// Ходьба: цикл 1,1 с, шаг 60 см (корень идёт вперёд сам, actor.speed), ноги - маятник с подъёмом ступни
inline std::vector<Matrix4x4> walkPose(const Skeleton& sk, float t) {
  std::vector<Matrix4x4> w(sk.tr.begin(), sk.tr.end());
  const float ph = t/1.1f*2.f*float(M_PI);
  aim(sk,w,"BIP01 L UPPERARM","BIP01 L FOREARM",Vec3( 0.15f,-1.f, 0.35f*std::sin(ph+float(M_PI))));
  aim(sk,w,"BIP01 L FOREARM", "BIP01 L HAND",   Vec3( 0.05f,-0.8f,0.5f));
  aim(sk,w,"BIP01 R UPPERARM","BIP01 R FOREARM",Vec3(-0.15f,-1.f, 0.35f*std::sin(ph)));
  aim(sk,w,"BIP01 R FOREARM", "BIP01 R HAND",   Vec3(-0.05f,-0.8f,0.5f));
  for(int k=0;k<2;++k) {
    const float s  = std::sin(ph + (k==0 ? 0.f : float(M_PI)));
    const float sw = std::max(0.f, std::cos(ph + (k==0 ? 0.f : float(M_PI))));  // нога в воздухе
    const char* th = k==0 ? "BIP01 L THIGH" : "BIP01 R THIGH";
    const char* ca = k==0 ? "BIP01 L CALF"  : "BIP01 R CALF";
    const char* ft = k==0 ? "BIP01 L FOOT"  : "BIP01 R FOOT";
    aim(sk,w,th,ca,Vec3(0,-1.f, 0.42f*s));
    aim(sk,w,ca,ft,Vec3(0,-1.f, 0.42f*s - 0.9f*sw));
    }
  float fy = 1e9f;
  for(auto nm:{"BIP01 L FOOT","BIP01 R FOOT"}) fy = std::min(fy, org(w[bone(sk,nm)]).y);
  for(auto& m:w) LimbIk::setOrigin(m, org(m)-Vec3(0,fy-8.f,0));
  return w;
  }

// Подъём с земли (вместо анимаций T_WOUNDEDB_2_STAND / T_WOUNDED_2_STAND игры): ключевые позы - на спине: лежит, сел с
// опорой на руки сзади, перекатился вперёд в присед (руки на коленях), встал; на животе: лежит, отжался на руках,
// колено под себя, встал. Между позами - плавно (направления костей). Конец - стоит, ступни у начала координат
struct GetUpKey {
  float t, pitch;   // с; наклон тела вокруг X, град (- назад: голова к -Z)
  Vec3  P;          // таз, см
  Vec3  th, ca, up, fo; // левые бедро, голень, плечо, предплечье (правые - зеркально по X)
  };

inline float getUpTime() { return 1.65f; }

inline std::vector<Matrix4x4> getUpPose(const Skeleton& sk, float t, bool back) {
  static const GetUpKey kb[] = {
    {0.00f,-88, {0,12,-45}, {0,-0.03f,1},     {0,-0.03f,1},     {0.3f,-0.1f,0.95f},   {0.3f,-0.1f,0.95f}},
    {0.55f,-25, {0,12,-40}, {0,0.55f,0.83f},  {0,-0.75f,0.66f}, {0.35f,-0.7f,-0.6f},  {0.05f,-0.97f,-0.2f}},
    {1.05f, 35, {0,42,-12}, {0.1f,-0.1f,0.99f},{0,-0.9f,-0.43f},{0.2f,-0.55f,0.8f},   {0,-0.6f,0.8f}},
    {1.65f,  0, {0,93,0},   {0.03f,-1,0.05f}, {0,-1,-0.05f},    {0.25f,-0.95f,0.12f}, {0.05f,-0.6f,0.8f}},
    };
  static const GetUpKey kf[] = {
    {0.00f, 88, {0,12,-40}, {0,-0.03f,-1},    {0,-0.03f,-1},    {0.3f,-0.1f,-0.95f},  {0.3f,-0.1f,-0.95f}},
    {0.50f, 70, {0,25,-40}, {0,-0.35f,-0.94f},{0,-0.05f,-1},    {0.15f,-0.98f,0.1f},  {0,-1,0.05f}},
    {1.00f, 40, {0,45,-30}, {0.1f,-0.75f,0.66f},{0,-0.15f,-0.99f},{0.15f,-0.9f,0.4f}, {0,-1,0.1f}},
    {1.65f,  0, {0,93,0},   {0.03f,-1,0.05f}, {0,-1,-0.05f},    {0.25f,-0.95f,0.12f}, {0.05f,-0.6f,0.8f}},
    };
  const GetUpKey* k = back ? kb : kf;
  t = std::clamp(t, 0.f, getUpTime());
  size_t i = 0;
  while(i+2<4 && t>k[i+1].t) ++i;
  const GetUpKey& a = k[i];
  const GetUpKey& b = k[i+1];
  float u = std::clamp((t-a.t)/(b.t-a.t), 0.f, 1.f);
  u = u*u*(3.f-2.f*u);
  auto lv = [u](Vec3 x, Vec3 y) { return Vec3::normalize(x*(1.f-u) + y*u); };
  const float pitch = (a.pitch*(1.f-u) + b.pitch*u)*float(M_PI)/180.f;
  const Vec3  P     = a.P*(1.f-u) + b.P*u;
  std::vector<Matrix4x4> w(sk.tr.begin(), sk.tr.end());
  const size_t pel = bone(sk,"BIP01 PELVIS");
  const Vec3   at  = org(w[pel]);
  for(auto& m:w)
    LimbIk::rotateAround(m, at, Vec3(1,0,0), pitch);
  auto mir = [](Vec3 v) { return Vec3(-v.x,v.y,v.z); };
  aim(sk,w,"BIP01 L THIGH","BIP01 L CALF", lv(a.th,b.th));
  aim(sk,w,"BIP01 L CALF", "BIP01 L FOOT", lv(a.ca,b.ca));
  aim(sk,w,"BIP01 R THIGH","BIP01 R CALF", mir(lv(a.th,b.th)));
  aim(sk,w,"BIP01 R CALF", "BIP01 R FOOT", mir(lv(a.ca,b.ca)));
  aim(sk,w,"BIP01 L UPPERARM","BIP01 L FOREARM", lv(a.up,b.up));
  aim(sk,w,"BIP01 L FOREARM", "BIP01 L HAND",    lv(a.fo,b.fo));
  aim(sk,w,"BIP01 R UPPERARM","BIP01 R FOREARM", mir(lv(a.up,b.up)));
  aim(sk,w,"BIP01 R FOREARM", "BIP01 R HAND",    mir(lv(a.fo,b.fo)));
  const Vec3 d = P - org(w[pel]);
  for(auto& m:w)
    LimbIk::setOrigin(m, org(m)+d);
  return w;
  }

struct Actor {
  const Skeleton&          sk;
  DynamicWorld&            dw;
  Vec3                     at;
  float                    rotY  = 0;
  float                    speed = 0;          // см/с вперёд (ходьба)
  float                    time  = 0;
  bool                     walking = false;
  float                    stumbleAt = -1;     // анимация игры «отшатнуться» (T_STUMBLE) с этого времени, с
  std::unique_ptr<Ragdoll> r;
  const InjuryRig*         rig = nullptr;
  Injury                   inj;
  // рука к ране, как Npc: после удара - через 0,2 с, держит hold с
  WoundReach               wound;              // рука к ране - как у Npc
  float                    woundHold = 2.f;
  bool                     fight     = false;  // в бою (оружие в руках): рука у раны - короче, как Npc::noteWound
  uint8_t                  freeArms  = 3;      // свободные руки (бит 0 - левая): с одноручным - 1
  float                    painPower = 0, painAt = -1, painSide = 0; // боль - как Npc::painNow
  std::vector<Matrix4x4>   anim, prevAnim, disp;
  std::vector<std::pair<Vec3,char>> marks;
  uint64_t                 dt = 33;
  // агония после гибели или оглушения (как Npc::startRagdoll -> ragdollThroes): life < 0 - без агонии
  float                    throesLife = -1;
  bool                     throesRise = true;
  uint32_t                 throesSeed = 1;
  size_t                   killBone   = size_t(-1); // рана от последнего удара (метка на кадрах): кость и точка в ней
  Vec3                     killLocal  = {};
  // толчок (S_Balance): люди рядом - опора для руки (как Npc::ragdollGrips); устоял - поза куклы переходит в анимацию
  std::vector<Ragdoll::Grip> grips;
  // тело для кукол рядом, пока своей куклы в физике нет (как Npc::tickRagdollProxy); id - как Npc::gripId
  std::unique_ptr<Ragdoll::Proxy> proxy;
  uint32_t                 id = 100;
  bool                     wantProxy = false;
  std::vector<Matrix4x4>   blendFrom;
  float                    blendAt   = -1;
  float                    blendTime = 0.45f;
  bool                     recovered = false;
  int                      steps     = 0;
  // встаёт (как Npc::ragdollGetUp): собирается лёжа по физике (getUpGather), поза куклы переходит в позу лёжа начала
  // подъёма (getUpBlend), потом - анимация подъёма; с этого времени, на спине или на животе
  float                    getUpAt   = -1;
  bool                     getUpBack = true;
  float                    getUpGather = 0.42f;
  float                    getUpBlend  = 0.26f;
  float                    getUpHold   = 0.68f; // анимация подъёма стоит на первом кадре (лёжа) столько, с
  bool                     getUpPhys = true;  // false - без физики: поза куклы сразу переходит в позу лёжа
  bool                     braceDemo = false; // стойка героя перед толчком: готов 0,4..0,6 с, удар в 0,8 с
  // процедурная поза вместо «анимации» (бой телом, подъём телом): мировые матрицы всех костей
  std::function<void(std::vector<Matrix4x4>&)> proc;
  bool                     stepWorld = true;  // второй человек в сцене физику не шагает (её шагает первый)
  int                      riseResult = 0;    // подъём телом: 1 - встал, -1 - не вышло

  Actor(DynamicWorld& dw, const Skeleton& sk, Vec3 at, float rotY=0):sk(sk), dw(dw), at(at), rotY(rotY) {
    rig = InjuryRig::of(sk);
    mkAnim();
    prevAnim = anim;
    disp     = anim;
    }

  Matrix4x4 obj() const { return objAt(at,rotY); }

  void mkAnim() {
    if(proc) {
      proc(anim);
      return;
      }
    auto local = walking ? walkPose(sk,time) : stancePose(sk);
    if(getUpAt>=0.f) {
      const float tu = time-getUpAt;
      local = getUpPose(sk, tu<getUpHold ? 0.f : tu-getUpHold, getUpBack);
      }
    if(stumbleAt>=0.f)
      stumble(local,time-stumbleAt);
    const Matrix4x4 o = obj();
    anim.resize(local.size());
    for(size_t i=0;i<local.size();++i) anim[i] = o*local[i];
    if(rig!=nullptr && inj.isActive())
      rig->apply(inj,o,anim.data(),anim.size());
    }

  // как T_STUMBLE в Gothic: игра обрывает анимацию (рывок без перехода) и за 0,1 с откидывает корпус и руки назад,
  // за 0,35 с возвращает - быстрая анимация, которую кукла не должна повторять рывком
  void stumble(std::vector<Matrix4x4>& w, float t) const {
    if(t<0.f || t>0.6f)
      return;
    const float a = (t<0.1f ? t/0.1f : std::max(0.f, 1.f-(t-0.1f)/0.35f));
    const size_t sp = bone(sk,"BIP01 SPINE1");
    const Vec3   at = org(w[sp]);
    const float  ang = -0.35f*a; // назад
    for(size_t i=0;i<w.size();++i)
      if(isUnder(sk,i,sp))
        LimbIk::rotateAround(w[i],at,Vec3(1,0,0),ang);
    }

  bool startActive() {
    r = Ragdoll::create(dw,sk,disp.data(),disp.size(),Ragdoll::S_Active,500,false);
    if(!r) return false;
    r->setTarget(anim,0);
    return true;
    }

  // убит (или оглушён) ударом - как Npc::startRagdoll: на ногах по физике - та же кукла оседает, иначе новая кукла
  // из позы на экране; стоял - оседает; скорость NPC (бег), затем толчок удара
  void kill(Vec3 atPt, Vec3 dir, float power, uint8_t kind = Ragdoll::H_Melee, Ragdoll::State st = Ragdoll::S_Dead,
            Vec3 vel = Vec3()) {
    Ragdoll::Hit h; h.at = atPt; h.dir = dir; h.power = power; h.kind = kind;
    {
    float best = 1e9f;
    for(size_t i=0;i<sk.nodes.size();++i) {
      if(sk.nodes[i].name.rfind("ZS_",0)==0) continue;
      const float d = (org(disp[i])-atPt).length();
      if(d<best) { best = d; killBone = i; }
      }
    Matrix4x4 inv = disp[killBone]; inv.inverse();
    killLocal = atPt; inv.project(killLocal);
    }
    if(r && r->state()==Ragdoll::S_Active) {
      r->setState(st);
      r->startSag();
      if(throesLife>=0.f)
        r->startThroes(throesLife,throesRise,throesSeed);
      r->hit(h);
      return;
      }
    r = Ragdoll::create(dw,sk,disp.data(),disp.size(),st,500,false);
    if(!r) return;
    r->setVelocity(vel);
    if(vel.length()<0.5f)
      r->startSag();
    if(throesLife>=0.f)
      r->startThroes(throesLife,throesRise,throesSeed);
    r->hit(h);
    }

  // толчок телом (герой с разбега, плечом или руками) - как Npc::ragdollShove
  void shove(Vec3 atPt, Vec3 dir, float speed, uint8_t free = 3) {
    if(!r) startActive();
    Ragdoll::Shove s; s.at = atPt; s.dir = dir; s.speed = speed; s.freeArms = free;
    // STEADY - как крепко стоит на ногах (в игре у каждого NPC своё, Npc::ragdollShove)
    s.steady = std::getenv("STEADY") ? float(std::atof(std::getenv("STEADY"))) : 1.f;
    r->shove(s);
    }

  // встаёт (как Npc::ragdollGetUp): на спине или на животе - по груди; NPC ставится так, чтобы таз и грудь первого
  // кадра подъёма легли на тело; кукла встаёт телом (S_GetUp) или, как раньше, поза переходит в анимацию
  void getUp(bool phys) {
    if(!r) return;
    getUpBack = r->chestFront().y>0.f;
    const auto f0  = getUpPose(sk,0,getUpBack);
    const Vec3 pA  = org(f0[bone(sk,"BIP01 PELVIS")]), cA = org(f0[bone(sk,"BIP01 SPINE2")]);
    const Vec3 pR  = r->rootPosition(), cR = r->chestPosition();
    const float aA = std::atan2(cA.x-pA.x, cA.z-pA.z), aR = std::atan2(cR.x-pR.x, cR.z-pR.z);
    rotY = (aR-aA)*180.f/float(M_PI);
    const float a = rotY*float(M_PI)/180.f;
    const Vec3  rA = Vec3(pA.x*std::cos(a)+pA.z*std::sin(a), 0, -pA.x*std::sin(a)+pA.z*std::cos(a));
    at = Vec3(pR.x-rA.x, 0, pR.z-rA.z);
    getUpPhys = phys;
    getUpAt   = time;
    getUpHold = getUpGather+getUpBlend;
    mkAnim();
    if(phys && r->startGetUp(getUpGather)) {
      r->setTarget(anim,0);
      return;
      }
    // без физики: поза куклы переходит в позу лёжа начала подъёма, потом анимация
    getUpPhys = false;
    getUpHold = getUpBlend+0.2f;
    blendFrom = disp;
    blendAt   = time;
    blendTime = getUpHold;
    r.reset();
    }

  // живой сбит ударом с ног или летит (обрыв, тролль) - как Npc::startRagdollFall
  void fall(Vec3 atPt, Vec3 dir, float power, uint8_t kind, Vec3 vel) {
    r = Ragdoll::create(dw,sk,disp.data(),disp.size(),Ragdoll::S_Fall,500,false);
    if(!r) return;
    r->setVelocity(vel);
    if(power>0.f) {
      Ragdoll::Hit h; h.at = atPt; h.dir = dir; h.power = power; h.kind = kind;
      r->hit(h);
      }
    }

  void hit(Vec3 atPt, Vec3 dir, float power, float damage, uint8_t kind = Ragdoll::H_Melee, bool stagger = true) {
    if(!r) startActive();
    Ragdoll::Hit h; h.at = atPt; h.dir = dir; h.power = power; h.damage = damage; h.kind = kind; h.freeArms = freeArms;
    r->hitActive(h,stagger);
    // рана для слоя позы - как Npc::noteWound
    InjuryRig::Wound w;
    if(rig!=nullptr && rig->woundAt(disp.data(),disp.size(),atPt,w)) {
      const int8_t arm = rig->reachArm(w,freeArms);
      if(arm>=0 && damage>=0.04f) {
        WoundReach::Wound wr;
        wr.bone = w.bone; wr.arm = arm; wr.zone = w.zone; wr.local = w.local; wr.normal = w.normal;
        const float hold = fight ? 1.5f + 3.f*damage : woundHold;
        wound.add(wr, int64_t(time*1000.f), int64_t(hold*1000.f));
        }
      const float c = painNow();
      const float p = std::max(std::clamp(0.3f + 2.5f*damage, 0.3f, 1.f), c);
      painPower = p;
      painAt    = time - 0.45f*c/p;
      painSide  = (w.zone==InjuryRig::Z_Leg) ? 0.f : (w.side==0 ? 1.f : -1.f)*(w.zone==InjuryRig::Z_Arm ? 0.4f : 0.7f);
      if(w.zone==InjuryRig::Z_Leg && damage>0.15f) {
        inj.leg  = w.side;
        inj.limp = std::clamp(damage*2.5f, 0.35f, 1.f);
        }
      }
    }

  float painNow() const {
    if(painAt<0.f)
      return 0.f;
    return painPower*LimbIk::envelope(time-painAt, 0.45f, 0.3f+1.2f*painPower, 1.6f);
    }

  void tick() {
    const float dts = float(dt)/1000.f;
    time += dts;
    inj.pain     = painNow();
    inj.painSide = painSide;
    if(walking) {
      const float a = rotY*float(M_PI)/180.f;
      at += Vec3(std::sin(a),0,std::cos(a))*(speed*dts);
      }
    if(braceDemo) {
      // человек в 60 см перед грудью; готовится за 0,2 с, удар - в 0,8 с, потом отпускает за 0,35 с
      inj.brace    = std::clamp((time-0.4f)/0.2f, 0.f, 1.f)*(time<1.06f ? 1.f : std::max(0.f, 1.f-(time-1.06f)/0.35f));
      inj.braceHit = time>=0.8f ? LimbIk::envelope(time-0.8f, 0.06f, 0.12f, 0.3f) : 0.f;
      const float ar = rotY*float(M_PI)/180.f;
      inj.braceAt  = at + Vec3(std::sin(ar),0,std::cos(ar))*60.f + Vec3(0,128,0);
      }
    if(wound.active()) {
      inj.reach  = wound.weight(int64_t(time*1000.f));
      inj.arm    = wound.cur.arm;  inj.bone   = wound.cur.bone;
      inj.local  = wound.cur.local; inj.normal = wound.cur.normal;
      }
    prevAnim = anim;
    if(r)
      r->setId(id);
    if(r && r->state()==Ragdoll::S_Active) {
      const Vec3 rm = r->takeRootMotion();
      at += Vec3(rm.x,0,rm.z);
      }
    if(r && r->lostBalance())
      r->setState(Ragdoll::S_Fall);
    // встаёт: собрался лёжа - кукла уходит из физики, поза переходит в позу лёжа начала подъёма (Npc::endRagdollGetUp),
    // потом анимация; кончилась - стоит
    if(getUpAt>=0.f && r && r->state()==Ragdoll::S_GetUp && time-getUpAt>=getUpGather) {
      blendFrom = disp;
      blendAt   = time;
      blendTime = getUpBlend;
      r.reset();
      }
    if(getUpAt>=0.f && time-getUpAt>=getUpHold+getUpTime()) {
      getUpAt = -1;
      recovered = true;
      }
    // ловит равновесие: NPC стоит там, где таз (как Npc::followRagdoll); устоял - поворачивается, как стоит тело, и
    // поза куклы переходит в анимацию (как Npc::tickRagdollBalance)
    if(r && r->state()==Ragdoll::S_Balance) {
      r->setGrips(grips);
      const Vec3 p = org(r->world()[0]);
      at.x = p.x; at.z = p.z;
      if(r->balanceRecovered()) {
        const Vec3 f = r->bodyFront();
        rotY = std::atan2(f.x,f.z)*180.f/float(M_PI);
        blendFrom = disp;
        blendAt   = time;
        recovered = true;
        r.reset();
        }
      }
    // цель - поза прошлого кадра со сдвигом NPC (как Npc::tickRagdollActive), затем физика, затем новый кадр
    if(r && r->isRising()) {
      r->tickRise(dt);
      if(r->riseDone() || r->riseFailed()) {
        riseResult = r->riseDone() ? 1 : -1;
        const Vec3 f = r->riseFacing(), p = r->riseFeet();
        rotY = std::atan2(f.x,f.z)*180.f/float(M_PI);
        at   = Vec3(p.x,0,p.z);
        blendFrom = disp;
        blendAt   = time;
        blendTime = 0.25f;
        recovered = true;
        r.reset();
        }
      }
    else if(r && r->isSimulated())
      r->setTarget(anim,dt);
    if(stepWorld)
      dw.tick(dt);
    mkAnim();
    display();
    }

  // вывод - как Pose::implMkSkeletonActive / setRagdoll
  void display() {
    disp = anim;
    if(std::getenv("RISE_TARGET") && r && r->risePose()) {
      disp = *r->risePose();
      return;
      }
    if(!r) {
      if(blendAt>=0.f && time<blendAt+blendTime && blendFrom.size()==disp.size()) {
        float w = 1.f-(time-blendAt)/blendTime;
        w = w*w*(3.f-2.f*w);
        for(size_t i=0;i<disp.size();++i) disp[i] = mixM(anim[i],blendFrom[i],w);
        }
      return;
      }
    if(r->state()==Ragdoll::S_Active) {
      if(!r->isSimulated()) return;
      std::vector<Matrix4x4> rel;
      Stance st;
      r->activePose(rel,st);
      std::vector<Matrix4x4> low = anim;
      if(rig!=nullptr && st.isActive())
        rig->applyStance(st,obj(),low.data(),low.size());
      const auto& modes = r->activeModes();
      const float w     = r->showWeight();
      const size_t root = r->rootBone();
      std::vector<int> relOf(sk.nodes.size(),-1);
      std::vector<uint8_t> under(sk.nodes.size(),0);
      for(size_t k=0;k<r->bones().size();++k) relOf[r->bones()[k]] = int(k);
      for(size_t i=0;i<sk.nodes.size();++i) {
        const size_t p = sk.nodes[i].parent;
        if(modes[i]==Ragdoll::B_Rel && relOf[i]>=0) {
          under[i] = 1;
          const Matrix4x4 phys = low[root]*rel[size_t(relOf[i])];
          disp[i] = mixM(low[i],phys,w);
          continue;
          }
        if(modes[i]==Ragdoll::B_Pinned || p>=sk.nodes.size() || !under[p]) { disp[i] = low[i]; continue; }
        // под физикой: локальная матрица анимации от выведенного родителя
        under[i] = 1;
        Matrix4x4 inv = anim[p]; inv.inverse();
        disp[i] = disp[p]*(inv*anim[i]);
        }
      return;
      }
    // лежит: ведомые кости - кукла, прочие - по иерархии от родителя
    const auto& drv = r->driven();
    std::vector<int> relOf(sk.nodes.size(),-1);
    for(size_t k=0;k<r->bones().size();++k) relOf[r->bones()[k]] = int(k);
    for(size_t i=0;i<sk.nodes.size();++i) {
      const size_t p = sk.nodes[i].parent;
      if(drv[i] && relOf[i]>=0) { disp[i] = r->world()[size_t(relOf[i])]; continue; }
      if(p<sk.nodes.size()) {
        Matrix4x4 inv = anim[p]; inv.inverse();
        disp[i] = disp[p]*(inv*anim[i]);
        }
      }
    }

  static Matrix4x4 mixM(const Matrix4x4& a, const Matrix4x4& b, float w) {
    if(w<=0.f) return a;
    if(w>=1.f) return b;
    // для стенда: оси и начало - линейно с нормировкой (на экране игры - кватернионы)
    Matrix4x4 m = a;
    for(int c=0;c<3;++c) {
      Vec3 x = LimbIk::axis(a,c)*(1.f-w) + LimbIk::axis(b,c)*w;
      const float l = LimbIk::axis(a,c).length();
      x = Vec3::normalize(x)*l;
      m.set(c,0,x.x); m.set(c,1,x.y); m.set(c,2,x.z);
      }
    LimbIk::setOrigin(m, org(a)*(1.f-w)+org(b)*w);
    return m;
    }

  // кадр для render.py: кости палочками
  void dump(FILE* f, const char* label, bool other = false) const {
    if(!other)
      std::fprintf(f,"F %.3f %s\n",double(time),label);
    // первая кость - таз (по ней камера следит)
    for(size_t i=0;i<sk.nodes.size();++i) {
      const std::string& nm = sk.nodes[i].name;
      const size_t p = sk.nodes[i].parent;
      if(p>=sk.nodes.size() || nm.rfind("ZS_",0)==0 || nm=="BIP01 PELVIS" || sk.nodes[p].name=="BIP01")
        continue;
      if(nm.find("SPINE")!=std::string::npos && sk.nodes[p].name.find("PELVIS")!=std::string::npos)
        continue;
      const std::string& pn = sk.nodes[p].name;
      char  kind = 'k';
      float rad  = 4;
      if(pn.find("SPINE")!=std::string::npos || pn.find("NECK")!=std::string::npos) { kind='p'; rad=9; }
      if(nm.find("CLAVICLE")!=std::string::npos) { kind='p'; rad=5; }
      if(pn.find("HEAD")!=std::string::npos || nm.find("HEAD")!=std::string::npos) { kind='h'; rad= nm.find("HEAD")!=std::string::npos ? 5 : 9; }
      if(pn.find("ARM")!=std::string::npos || pn.find("HAND")!=std::string::npos || pn.find("CLAVICLE")!=std::string::npos) { kind='a'; rad=4.5f; }
      if(pn.find("THIGH")!=std::string::npos || pn.find("CALF")!=std::string::npos || pn.find("FOOT")!=std::string::npos) { kind='l'; rad=6; }
      if(nm.find("THIGH")!=std::string::npos) { kind='p'; rad=8; }
      const Vec3 a = org(disp[p]), b = org(disp[i]);
      if(i==bone(sk,"BIP01 SPINE")) continue;
      if(other) kind = 'o';
      std::fprintf(f,"B %.1f %.1f %.1f %.1f %.1f %.1f %.1f %c\n",double(a.x),double(a.y),double(a.z),double(b.x),double(b.y),double(b.z),double(rad),kind);
      }
    // голова - шар
    {
    const size_t h = bone(sk,"BIP01 HEAD");
    if(h!=size_t(-1)) {
      const Vec3 a = org(disp[h]);
      Vec3 up = Vec3::normalize(org(disp[h]) - org(disp[sk.nodes[h].parent]))*12.f;
      std::fprintf(f,"B %.1f %.1f %.1f %.1f %.1f %.1f %.1f %c\n",double(a.x),double(a.y),double(a.z),double(a.x+up.x),double(a.y+up.y),double(a.z+up.z),9.0,other ? 'o' : 'h');
      }
    }
    for(auto& m:marks)
      std::fprintf(f,"M %.1f %.1f %.1f %c\n",double(m.first.x),double(m.first.y),double(m.first.z),m.second);
    }
  };

}
