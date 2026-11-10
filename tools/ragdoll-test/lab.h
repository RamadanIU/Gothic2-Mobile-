// Gothic II Mobile: площадка - сцены боя телом для 3D-просмотра и проверок (./ragtest lab <сцена> out.json).
// Часы ходов - как у анимаций Gothic II: стадия серии ~1 с, миг удара (OPTIMAL_FRAME) - через ~0,28 с от её начала,
// дальше окно серии и возврат; блок - короткая анимация. Сцены - по одному виду хода, чтобы каждый можно было
// рассмотреть: стойки (кулачная - рядом с настоящей стойкой игры S_FISTRUN), серия, удары слева и справа, блок,
// дуэль, двуручник, кулаки, удар с разбега.
#pragma once

#include "fight.h"
#include "gothicskel.h"
#include "rec.h"

#include <functional>
#include <map>
#include <string>
#include <vector>

namespace lab {

using bench::Fighter;

struct Seg {
  float             at;
  FightBody::Move   move;
  int               stages;
  float             stageLen, strikeAt;
  };

inline void drive(Fighter& f, const std::vector<Seg>& segs, float t) {
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

struct Result {
  int   hits[2] = {}, blocks[2] = {};
  bool  fell    = false;
  float lagMax  = 0;   // см: кисть куклы от цели в окне удара
  float stoodAt = -1;  // с от потери сознания до «встал» (<0 - не вставал)
  };

struct Scene {
  std::string name, group, title, note;
  float       len = 5;
  };

inline const std::vector<Scene>& scenes() {
  static const std::vector<Scene> s = {
    {"stance1h",   "Стойки", "Стойка с мечом", "Двое стоят в стойке: дыхание, перенос веса, клинок чуть ходит.", 4.f},
    {"stancefist", "Стойки", "Кулачная стойка и стойка игры", "Слева - наша стойка, справа - кадры настоящей анимации Gothic II (S_FISTRUN) на том же скелете.", 4.f},
    {"stance2h",   "Стойки", "Стойка с двуручным мечом", "", 4.f},
    {"combo1h",    "Меч", "Серия из четырёх ударов", "Справа сверху, слева сверху, сверху вниз, справа в бок. Противник стоит и не защищается.", 6.f},
    {"sides1h",    "Меч", "Удары слева и справа", "", 4.6f},
    {"parry1h",    "Меч", "Блок", "Синий бьёт, рыжий ставит клинок поперёк удара.", 5.f},
    {"duel1h",     "Меч", "Дуэль", "Обмен ударами и блоками.", 7.f},
    {"run1h",      "Меч", "Удар с разбега", "", 4.f},
    {"combo2h",    "Двуручный меч", "Серия двуручным мечом", "", 6.5f},
    {"parry2h",    "Двуручный меч", "Блок двуручным мечом", "", 5.f},
    {"fists",      "Кулаки", "Кулаки: прямой, прямой, боковой", "", 5.f},
    {"fistblock",  "Кулаки", "Кулаки: защита", "", 4.5f},
    {"knockout",   "Потеря сознания и подъём", "Серия до потери сознания, подъём", "Синий получает удары, пока не потеряет сознание, падает, лежит и встаёт сам.", 14.f},
    {"knockfists", "Потеря сознания и подъём", "Нокаут кулаками, подъём", "", 12.f},
    };
  return s;
  }

inline const Scene* find(const std::string& name) {
  for(auto& s:scenes())
    if(s.name==name)
      return &s;
  return nullptr;
  }

// Сцена: rec - запись для просмотра (может не быть), res - итог для проверок
inline int run(const std::string& name, const Skeleton& hum, bench::Recorder* rec, Result* res) {
  const Scene* sc = find(name);
  if(sc==nullptr) {
    std::printf("unknown lab scene %s\n",name.c_str());
    return 1;
    }
  zenkit::Mesh mesh;
  benchBoxes.clear();
  DynamicWorld dw(nullptr,mesh);
  float dist = 150;
  Fighter A(dw,hum,Vec3(0,0,0),0.f);
  Fighter B(dw,hum,Vec3(0,0,dist),180.f);
  B.a.stepWorld = false;
  B.a.id = 101;
  A.foe = &B; B.foe = &A;
  std::vector<Seg> sa, sb;
  // часы Gothic: стадия 1,0 с, удар через 0,28 с; двуручник медленнее; кулаки быстрее
  const float SL = 1.0f, ST = 0.28f;
  auto arm = [&](FightBody::Weapon w, float bl) {
    A.weapon = B.weapon = w;
    A.bladeLen = B.bladeLen = bl;
    };
  arm(FightBody::W_1H,90);
  bool refAnim = false;
  std::function<void(float)> script;
  if(name=="stance1h") {
    }
  else if(name=="stance2h") {
    arm(FightBody::W_2H,125);
    }
  else if(name=="stancefist") {
    arm(FightBody::W_Fist,0);
    A.foe = nullptr; B.foe = nullptr;
    B.a.at = Vec3(-130,0,0);
    B.yaw  = 0;
    refAnim = true;
    }
  else if(name=="combo1h") {
    sa = {{0.7f,FightBody::M_Attack,4,SL,ST}};
    }
  else if(name=="sides1h") {
    sa = {{0.6f,FightBody::M_AttackL,1,1.2f,0.32f}, {2.3f,FightBody::M_AttackR,1,1.2f,0.32f}};
    }
  else if(name=="parry1h") {
    sb = {{0.8f,FightBody::M_Attack,2,SL,ST}, {3.3f,FightBody::M_AttackR,1,1.2f,0.32f}};
    sa = {{0.8f,FightBody::M_Parade,1,0.6f,-1}, {1.8f,FightBody::M_Parade,1,0.6f,-1}, {3.3f,FightBody::M_Parade,1,0.7f,-1}};
    }
  else if(name=="duel1h") {
    sa = {{0.6f,FightBody::M_Attack,2,SL,ST}, {3.5f,FightBody::M_Parade,1,0.6f,-1}, {4.6f,FightBody::M_AttackR,1,1.2f,0.32f}};
    sb = {{0.65f,FightBody::M_Parade,1,0.6f,-1}, {2.9f,FightBody::M_Attack,1,SL,ST}, {3.5f+0.55f,FightBody::M_AttackL,1,1.2f,0.32f}};
    }
  else if(name=="run1h") {
    dist = 560; B.a.at = Vec3(0,0,dist);
    sa = {{1.15f,FightBody::M_AttackRun,1,1.1f,0.36f}};
    script = [&](float t) {
      A.vel = (t>0.2f && B.a.at.z-A.a.at.z>150.f) ? Vec3(0,0,std::min(380.f,(t-0.2f)*900.f)) : Vec3();
      };
    }
  else if(name=="combo2h") {
    arm(FightBody::W_2H,125);
    dist = 175; B.a.at = Vec3(0,0,dist);
    sa = {{0.7f,FightBody::M_Attack,3,1.25f,0.40f}};
    }
  else if(name=="parry2h") {
    arm(FightBody::W_2H,125);
    dist = 175; B.a.at = Vec3(0,0,dist);
    sb = {{0.8f,FightBody::M_Attack,2,1.25f,0.40f}};
    sa = {{0.9f,FightBody::M_Parade,1,0.7f,-1}, {2.15f,FightBody::M_Parade,1,0.7f,-1}};
    }
  else if(name=="knockout") {
    sa = {{0.7f,FightBody::M_Attack,4,SL,ST}};
    B.hp = 0.16f;
    }
  else if(name=="knockfists") {
    arm(FightBody::W_Fist,0);
    dist = 100; B.a.at = Vec3(0,0,dist);
    sa = {{0.7f,FightBody::M_Attack,3,0.75f,0.2f}};
    B.hp = 0.25f;
    }
  else if(name=="fists") {
    arm(FightBody::W_Fist,0);
    dist = 100; B.a.at = Vec3(0,0,dist);
    sa = {{0.7f,FightBody::M_Attack,3,0.75f,0.2f}};
    }
  else if(name=="fistblock") {
    arm(FightBody::W_Fist,0);
    dist = 100; B.a.at = Vec3(0,0,dist);
    sb = {{0.7f,FightBody::M_Attack,3,0.75f,0.2f}};
    sa = {{0.7f,FightBody::M_Parade,1,2.3f,-1}};
    }
  if(refAnim) {
    // второй - не кукла: кадры анимации игры на том же скелете
    B.a.proc = [&B,&hum](std::vector<Matrix4x4>& out) {
      std::vector<Matrix4x4> local;
      gothic::fistRunPose(hum,B.a.time,local);
      const Matrix4x4 o = B.a.obj();
      out.resize(local.size());
      for(size_t i=0; i<local.size(); ++i)
        out[i] = o*local[i];
      };
    B.a.mkAnim();
    B.a.disp = B.a.anim;
    }
  A.a.startActive(); A.a.r->setCombat(true);
  auto gh = [](FightBody::Weapon w) { return uint8_t(w==FightBody::W_Fist ? 3 : (w==FightBody::W_2H ? 3 : 2)); };
  A.a.r->setGripHands(gh(A.weapon));
  if(!refAnim) {
    B.a.startActive(); B.a.r->setCombat(true);
    B.a.r->setGripHands(gh(B.weapon));
    }
  const uint64_t stepMs = std::getenv("DT") ? uint64_t(std::max(1,std::atoi(std::getenv("DT")))) : 33u;
  A.a.dt = B.a.dt = stepMs;
  if(rec!=nullptr) {
    rec->begin(name,sc->title,{"A","B"});
    rec->note = sc->note;
    }
  int lastFrame = -1;
  for(float t=0; t<sc->len; t+=float(stepMs)/1000.f) {
    drive(A,sa,t);
    drive(B,sb,t);
    if(script)
      script(t);
    const float dts = float(stepMs)/1000.f;
    A.advance(dts); B.advance(dts);
    A.a.tick();
    if(refAnim) {
      B.a.time += dts;
      B.a.mkAnim();
      B.a.disp = B.a.anim;
      } else {
      B.a.tick();
      }
    A.sweep();
    if(!refAnim)
      B.sweep();
    // здоровье кончилось - потеря сознания: тело падает куклой (как Npc::onNoHealth -> startRagdoll), удар - последний;
    // через 3 с после того, как легло, встаёт само (как Npc::ragdollGetUp -> startRagdollRise)
    for(auto* f:{&A,&B}) {
      if(f->hp<=0.f && !f->down && f->a.r) {
        f->down   = true;
        f->downAt = t;
        const auto& h = f->foe->lastHit;
        f->a.throesLife = -1;
        f->a.kill(h.at, h.dir, h.power, Ragdoll::H_Melee, Ragdoll::S_Unconscious);
        f->a.proc = nullptr;
        if(rec!=nullptr)
          rec->event(t,"note",h.at,std::string(f==&A ? "A" : "B")+": потерял сознание",f==&A ? 0 : 1);
        }
      if(f->down && !f->rising && f->a.r && t-f->downAt>1.2f && (f->a.r->isResting() || t-f->downAt>4.f) && t-f->downAt>3.0f) {
        f->rising = f->a.r->startRise(1.f);
        if(rec!=nullptr)
          rec->event(t,"note",f->a.r->rootPosition(),std::string(f==&A ? "A" : "B")+(f->rising ? ": встаёт" : ": не может встать"),f==&A ? 0 : 1);
        if(!f->rising)
          f->downAt = t;
        }
      if(f->rising && f->a.recovered && !f->stood) {
        f->stood = true;
        if(rec!=nullptr)
          rec->event(t,"note",f->a.at+Vec3(0,100,0),std::string(f==&A ? "A" : "B")+(f->a.riseResult>0 ? ": встал" : ": не встал сам"),f==&A ? 0 : 1);
        if(res!=nullptr)
          res->stoodAt = t-f->downAt;
        }
      }
    if(res!=nullptr) {
      if((A.a.r && A.a.r->state()==Ragdoll::S_Fall) || (B.a.r && B.a.r->state()==Ragdoll::S_Fall))
        res->fell = true;
      for(auto* f:{&A,&B})
        if(f->fb.striking()) {
          const size_t h = f->rig->handBone(1);
          res->lagMax = std::max(res->lagMax, (bench::org(f->a.disp[h])-bench::org(f->a.anim[h])).length());
          }
      }
    if(std::getenv("LABDUMP") && std::abs(t-float(std::atof(std::getenv("LABDUMP"))))<dts*0.5f) {
      // суставы относительно точки NPC на земле, в его системе (x - влево, y - вверх, z - вперёд): цель позы и тело
      for(auto* f:{&A,&B}) {
        const Vec3 fw = f->fwd(), up(0,1,0);
        const Vec3 lf = Vec3::crossProduct(up,fw)*f->rig->handedness();
        auto loc = [&](const Vec3& w) { const Vec3 d = w-f->a.at; return Vec3(Vec3::dotProduct(d,lf),d.y,Vec3::dotProduct(d,fw)); };
        std::printf("%s (t=%.2f)\n", f==&A ? "A" : "B", double(t));
        for(auto nm:{"BIP01 PELVIS","BIP01 SPINE2","BIP01 NECK","BIP01 HEAD","BIP01 L UPPERARM","BIP01 L FOREARM","BIP01 L HAND",
                     "BIP01 R UPPERARM","BIP01 R FOREARM","BIP01 R HAND","BIP01 L THIGH","BIP01 L CALF","BIP01 L FOOT","BIP01 L TOE0",
                     "BIP01 R THIGH","BIP01 R CALF","BIP01 R FOOT","BIP01 R TOE0"}) {
          const size_t b = bench::bone(f->a.sk,nm);
          const Vec3 p = loc(bench::org(f->a.anim[b])), q = loc(bench::org(f->a.disp[b]));
          std::printf("  %-18s target (%6.1f %6.1f %6.1f)  body (%6.1f %6.1f %6.1f)\n",nm,double(p.x),double(p.y),double(p.z),double(q.x),double(q.y),double(q.z));
          }
        }
      }
    const int frame = int(t/0.033f+1e-4f);
    if(frame==lastFrame || rec==nullptr)
      continue;
    lastFrame = frame;
    static const char* mv[] = {"стойка","удар","удар слева","удар справа","удар с разбега","добивание","блок","отскок","достаёт","убирает","задет"};
    char label[160];
    std::snprintf(label,sizeof(label),"A: %s%s   B: %s%s",mv[A.clock.move],A.fb.striking()?" *":"",
                  refAnim ? "анимация игры" : mv[B.clock.move],B.fb.striking()?" *":"");
    rec->frame(t,label);
    rec->body(A.a);
    rec->body(B.a);
    rec->ghost(A.a.sk,A.a.anim);
    rec->ghost(B.a.sk,B.a.anim);
    int who = 0;
    for(auto* fg:{&A,&B}) {
      if(fg->hasPrev && fg->weapon!=FightBody::W_Fist) {
        Vec3 b0, b1; fg->blade(b0,b1);
        const Vec3 h = bench::org(fg->a.disp[fg->rig->slotBone(1)<fg->a.disp.size() ? fg->rig->slotBone(1) : fg->rig->handBone(1)]);
        rec->blade(who,h,b1);
        }
      for(; fg->recLog<fg->fx.size(); ++fg->recLog)
        rec->event(t, fg->fx[fg->recLog].second=='y' ? "block" : (fg->fx[fg->recLog].second=='o' ? "note" : "hit"), fg->fx[fg->recLog].first,
                   std::string(who==0 ? "A: " : "B: ") + (fg->recLog<fg->log.size() ? fg->log[fg->recLog] : ""), who);
      ++who;
      }
    }
  if(res!=nullptr) {
    res->hits[0] = A.hits; res->blocks[0] = A.blocks;
    res->hits[1] = B.hits; res->blocks[1] = B.blocks;
    }
  if(rec==nullptr && res==nullptr) {
    for(auto& l:A.log) std::printf("A %s\n",l.c_str());
    for(auto& l:B.log) std::printf("B %s\n",l.c_str());
    }
  return 0;
  }

}
