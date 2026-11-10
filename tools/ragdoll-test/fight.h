// Gothic II Mobile: стенд боя телом (game/fightbody.h): два человека с оружием, ходы - по «часам» (как анимации игры:
// стадии серии, миг удара, блок), поза - процедурная (BodyRig), тело - кукла; клинок - отрезок от слота кисти,
// попадание и блок - по касанию клинка (как Npc::tickBlade).
#pragma once

#include "actor.h"
#include "game/fightbody.h"
#include "game/bladework.h"
#include "graphics/mesh/bodyrig.h"

namespace bench {

inline float segSegDist(const Vec3& p1, const Vec3& q1, const Vec3& p2, const Vec3& q2, Vec3* at = nullptr) {
  const Vec3 d1 = q1-p1, d2 = q2-p2, r = p1-p2;
  const float a = Vec3::dotProduct(d1,d1), e = Vec3::dotProduct(d2,d2), f = Vec3::dotProduct(d2,r);
  float s = 0, t = 0;
  if(a<1e-6f && e<1e-6f) { if(at) *at = p1; return (p1-p2).length(); }
  if(a<1e-6f) { t = std::clamp(f/e,0.f,1.f); }
  else {
    const float c = Vec3::dotProduct(d1,r);
    if(e<1e-6f) { s = std::clamp(-c/a,0.f,1.f); }
    else {
      const float b = Vec3::dotProduct(d1,d2), den = a*e-b*b;
      s = den>1e-6f ? std::clamp((b*f-c*e)/den,0.f,1.f) : 0.f;
      t = (b*s+f)/e;
      if(t<0.f) { t = 0; s = std::clamp(-c/a,0.f,1.f); }
      else if(t>1.f) { t = 1; s = std::clamp((b-c)/a,0.f,1.f); }
      }
    }
  const Vec3 c1 = p1+d1*s, c2 = p2+d2*t;
  if(at) *at = (c1+c2)*0.5f;
  return (c1-c2).length();
  }

struct Fighter {
  Actor               a;
  const BodyRig*      rig = nullptr;
  FightBody           fb;
  FightBody::Weapon   weapon = FightBody::W_1H;
  float               bladeLen = 90;
  FightBody::Clock    clock;
  Vec3                vel = {};       // см/с - NPC идёт (корень)
  float               yaw = 0;        // град, как Actor::rotY
  Fighter*            foe = nullptr;
  Vec3                pA = {}, pB = {};
  bool                hasPrev = false;
  uint32_t            spent = 0;      // удар, который уже попал или упёрся
  uint32_t            beat  = 0;      // удар, который сбил клинок противника (и идёт дальше)
  float               keep  = 1;      // доля силы после сбива
  int                 beats = 0;
  float               hp    = 1;      // здоровье, доля
  bool                down  = false, rising = false, stood = false;
  float               downAt = 0;
  BladeWork::Blade    prevBlade;
  BladeWork::BodyHit  lastHit;
  int                 hits = 0, blocks = 0;
  std::vector<std::pair<Vec3,char>> fx; // вспышки: попадания (r), блоки (y)
  std::vector<std::string>          log;
  size_t                            recLog = 0; // сколько событий уже отдано записи площадки
  float               dmg = 0.12f;

  Fighter(DynamicWorld& dw, const Skeleton& sk, Vec3 at, float rotY):a(dw,sk,at,rotY), yaw(rotY) {
    rig = BodyRig::of(sk);
    a.fight = true; a.freeArms = 1;
    a.proc = [this](std::vector<Matrix4x4>& out) { pose(out); };
    }

  Vec3 fwd() const { const float r = yaw*float(M_PI)/180.f; return Vec3(std::sin(r),0,std::cos(r)); }

  // клинок: от слота правой кисти по оси хвата (bladeLen); кулак - от запястья до костяшек
  void blade(Vec3& b0, Vec3& b1, bool target = false) const {
    const auto& d = target ? a.anim : a.disp;
    if(weapon==FightBody::W_Fist) {
      // как Npc::bladeSegment: от запястья по предплечью до костяшек - той рукой, которой бьёт
      const size_t h  = rig->handBone(fb.strikeHand()==0 ? 0 : 1);
      const size_t el = a.sk.nodes[h].parent;
      Vec3 dir = org(d[h])-org(d[el]);
      dir = dir.length()>1.f ? Vec3::normalize(dir) : Vec3(0,0,1);
      b0 = org(d[h]) - dir*4.f;
      b1 = org(d[h]) + dir*9.f;
      return;
      }
    const size_t h  = rig->handBone(1), sl = rig->slotBone(1);
    const Vec3   ax = rig->defaultGripAxis(1);
    const Matrix4x4& m = d[h];
    Vec3 w = LimbIk::axis(m,0)*ax.x/LimbIk::axis(m,0).length() + LimbIk::axis(m,1)*ax.y/LimbIk::axis(m,1).length() +
             LimbIk::axis(m,2)*ax.z/LimbIk::axis(m,2).length();
    w = Vec3::normalize(w);
    const Vec3 o = org(d[sl]);
    b0 = o + w*(0.15f*bladeLen);
    b1 = o + w*bladeLen;
    }

  void pose(std::vector<Matrix4x4>& out) {
    FightBody::Input in;
    in.weapon   = weapon;
    in.clock    = clock;
    in.root     = a.at;
    in.fwd      = fwd();
    in.vel      = vel;
    in.bladeLen = bladeLen;
    in.seed     = a.id*7919u;
    if(foe) {
      in.target = true;
      in.tChest = org(foe->a.disp[bone(a.sk,"BIP01 SPINE2")]);
      in.tHead  = org(foe->a.disp[bone(a.sk,"BIP01 HEAD")]);
      in.tPelvis= org(foe->a.disp[bone(a.sk,"BIP01 PELVIS")]);
      Vec3 aAt, aDir;
      if(foe->fb.strikeAim(aAt,aDir)) {
        in.threatAimed = true; in.threatAt = aAt; in.threatDir = aDir;
        }
      if(foe->hasPrev) {
        // клинок противника и его скорость (как Npc::tickFightBody)
        const BladeWork::Blade bn = foe->bladeNow();
        in.threat  = true;
        in.threatA = bn.grip;
        in.threatB = bn.tip;
        if(foe->fb.striking())
          in.threatVel = ((bn.grip+bn.tip)*0.5f - (foe->prevBlade.grip+foe->prevBlade.tip)*0.5f)/(float(a.dt)/1000.f);
        }
      }
    BodyRig::Spec sp;
    fb.tick(*rig,in,float(a.dt)/1000.f,sp);
    rig->build(sp,1.f,out);
    if(a.r)
      a.r->setGripPrecision(fb.precision());
    }

  // перед кадром: часы и перемещение
  void advance(float dts) {
    a.at += vel*dts;
    clock.t += dts;
    }

  // клинок в позе тела - как Npc::bladeSegment: от хвата (слот кисти) до острия; кулак - от запястья до костяшек
  BladeWork::Blade bladeNow() const {
    BladeWork::Blade bl;
    bl.weapon = uint8_t(weapon);
    if(weapon==FightBody::W_Fist) {
      blade(bl.grip,bl.tip);
      bl.radius = 5.f;
      return bl;
      }
    Vec3 b0, b1;
    blade(b0,b1);
    const size_t sl = rig->slotBone(1);
    bl.grip   = org(a.disp[sl<a.disp.size() ? sl : rig->handBone(1)]);
    bl.tip    = b1;
    bl.radius = 3.f;
    return bl;
    }

  // после кадра: клинок прошёл от прошлого места к нынешнему - что он задел, считает game/bladework (тот же код, что
  // в игре): клинок противника (блок или сбив) или тело (удар по месту)
  void sweep() {
    const BladeWork::Blade cur = bladeNow();
    const float dts = float(a.dt)/1000.f;
    if(!hasPrev) { prevBlade = cur; pA = cur.grip; pB = cur.tip; hasPrev = true; return; }
    if(foe!=nullptr && !down && !foe->down && fb.striking() && fb.strikeId()!=spent && foe->a.r) {
      const uint32_t id = fb.strikeId();
      BladeWork::Sweep sw;
      sw.from = prevBlade; sw.to = cur; sw.dt = dts;
      char buf[200];
      if(foe->hasPrev && id!=beat) {
        BladeWork::Sweep so;
        so.from = foe->prevBlade; so.to = foe->bladeNow(); so.dt = dts;
        BladeWork::BladeHit bh;
        if(BladeWork::hitBlade(sw,so,bh)) {
          const Vec3 vd = bh.vel.length()>1e-3f ? Vec3::normalize(bh.vel) : fwd();
          if(foe->fb.blocking()) {
            // блок: удар остановлен, клинок отскакивает; рука блокирующего получает отдачу
            const float kick = BladeWork::parryKick(bh,uint8_t(weapon),uint8_t(foe->weapon));
            if(a.r) { a.r->softenGrip(0.16f); a.r->nudge(org(a.disp[rig->handBone(1)]), vd*(-0.25f*bh.speed)); }
            foe->a.r->softenGrip(0.14f);
            foe->a.r->nudge(org(foe->a.disp[foe->rig->handBone(1)]), vd*kick);
            fb.impact(FightBody::I_Blocked);
            spent = id;
            blocks++;
            fx.push_back({bh.at,'y'});
            std::snprintf(buf,sizeof(buf),"блок: клинок о клинок (%.0f%% от гарды), %.0f м/с",double(bh.uOther*100.f),double(bh.speed));
            log.push_back(buf);
            } else {
            // клинок противника не в блоке: сбит в сторону, удар идёт дальше слабее
            keep = BladeWork::beatKeep(bh,uint8_t(weapon),uint8_t(foe->weapon));
            foe->a.r->softenGrip(0.3f);
            foe->a.r->nudge(org(foe->a.disp[foe->rig->handBone(1)]), vd*(0.22f*bh.speed));
            if(a.r) a.r->softenGrip(0.05f);
            fb.impact(FightBody::I_Beat);
            beat = id;
            beats++;
            fx.push_back({bh.at,'o'});
            std::snprintf(buf,sizeof(buf),"клинок противника сбит, %.0f м/с (удар теряет %.0f%%)",double(bh.speed),double((1.f-keep)*100.f));
            log.push_back(buf);
            }
          }
        }
      if(spent!=id) {
        BladeWork::Body body;
        body.sk = &foe->a.sk; body.bones = foe->a.disp.data(); body.count = foe->a.disp.size();
        BladeWork::BodyHit h;
        if(BladeWork::hitBody(sw,body,h)) {
          const bool armPart = (h.part==BladeWork::BP_Forearm || h.part==BladeWork::BP_Hand || h.part==BladeWork::BP_UpperArm);
          if(weapon==FightBody::W_Fist && armPart && foe->fb.blocking()) {
            // кулаком в поставленные предплечья - блок
            foe->a.r->nudge(h.at, h.dir*(0.12f*h.speed));
            fb.impact(FightBody::I_Blocked);
            blocks++;
            fx.push_back({h.at,'y'});
            std::snprintf(buf,sizeof(buf),"блок: кулак в предплечья, %.0f м/с",double(h.speed));
            log.push_back(buf);
            }
          else if(h.wounds) {
            const float k    = (beat==id ? keep : 1.f);
            const float loss = dmg*h.damageMul()*k;
            foe->hp = std::max(0.f, foe->hp-loss);
            foe->a.hit(h.at, h.dir, h.power*(0.6f+0.4f*k), loss);
            if(a.r) { a.r->softenGrip(0.14f); a.r->nudge(h.at, h.dir*(-0.12f*h.speed)); }
            fb.impact(FightBody::I_Body);
            hits++;
            lastHit = h;
            fx.push_back({h.at,'r'});
            std::snprintf(buf,sizeof(buf),"%s %s: %s x%.1f, %.0f м/с, урон %.0f%% (x%.2f)",BladeWork::kindTitle(h.kind),BladeWork::bladeTitle(h.blade),
                          BladeWork::partTitle(h.part),double(h.mulPart),double(h.speed),double(loss*100.f),double(h.damageMul()*k));
            log.push_back(buf);
            }
          else {
            // задел без силы: толкнул
            foe->a.r->nudge(h.at, h.dir*0.4f);
            fx.push_back({h.at,'o'});
            std::snprintf(buf,sizeof(buf),"касание без силы: %s, %.1f м/с",BladeWork::partTitle(h.part),double(h.speed));
            log.push_back(buf);
            }
          spent = id;
          }
        }
      }
    prevBlade = cur;
    pA = cur.grip; pB = cur.tip;
    }

  void dumpBlade(FILE* f, char kind) const {
    if(!hasPrev) return;
    Vec3 b0, b1; blade(b0,b1);
    const Vec3 h = org(a.disp[rig->slotBone(1)<a.disp.size() ? rig->slotBone(1) : rig->handBone(1)]);
    if(weapon!=FightBody::W_Fist)
      std::fprintf(f,"B %.1f %.1f %.1f %.1f %.1f %.1f %.1f %c\n",double(h.x),double(h.y),double(h.z),double(b1.x),double(b1.y),double(b1.z),1.8,kind);
    }
  };

}
