// Gothic II Mobile: стенд боя телом (game/fightbody.h): два человека с оружием, ходы - по «часам» (как анимации игры:
// стадии серии, миг удара, блок), поза - процедурная (BodyRig), тело - кукла; клинок - отрезок от слота кисти,
// попадание и блок - по касанию клинка (как Npc::tickBlade).
#pragma once

#include "actor.h"
#include "game/fightbody.h"
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
  int                 hits = 0, blocks = 0;
  std::vector<std::pair<Vec3,char>> fx; // вспышки: попадания (r), блоки (y)
  std::vector<std::string>          log;
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
      const size_t h = rig->handBone(1);
      const size_t f = bone(a.sk,"BIP01 R FINGER1");
      b0 = org(d[h]);
      b1 = f!=size_t(-1) ? org(d[f]) : org(d[h]);
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
    if(foe) {
      in.target = true;
      in.tChest = org(foe->a.disp[bone(a.sk,"BIP01 SPINE2")]);
      in.tHead  = org(foe->a.disp[bone(a.sk,"BIP01 HEAD")]);
      in.tPelvis= org(foe->a.disp[bone(a.sk,"BIP01 PELVIS")]);
      Vec3 aAt, aDir;
      if(foe->fb.strikeAim(aAt,aDir)) {
        in.threatAimed = true; in.threatAt = aAt; in.threatDir = aDir;
        }
      if(foe->hasPrev && foe->fb.striking()) {
        Vec3 b0, b1; foe->blade(b0,b1);
        in.threat = true; in.threatA = b0; in.threatB = b1;
        in.threatVel = ((b0+b1)*0.5f - (foe->pA+foe->pB)*0.5f)/(float(a.dt)/1000.f);
        } else if(foe->hasPrev) {
        Vec3 b0, b1; foe->blade(b0,b1);
        in.threat = true; in.threatA = b0; in.threatB = b1;
        }
      }
    BodyRig::Spec sp;
    fb.tick(*rig,in,float(a.dt)/1000.f,sp);
    rig->build(sp,1.f,out);
    }

  // перед кадром: часы и перемещение
  void advance(float dts) {
    a.at += vel*dts;
    clock.t += dts;
    }

  // после кадра: клинок прошёл от прошлого места к нынешнему - касание клинка противника (блок) или тела (удар)
  void sweep() {
    Vec3 b0, b1;
    blade(b0,b1);
    if(std::getenv("FTRACE") && fb.striking() && foe) {
      const Vec3 h  = org(a.disp[rig->handBone(1)]), ht = org(a.anim[rig->handBone(1)]);
      const Vec3 c  = org(foe->a.disp[bone(a.sk,"BIP01 SPINE2")]);
      Vec3 t0, t1; blade(t0,t1,true);
      std::printf("  %c t=%.2f stage=%d hand=(%.0f %.0f %.0f) want=(%.0f %.0f %.0f) tip=(%.0f %.0f %.0f) foeChest=(%.0f %.0f %.0f) tgtTip=(%.0f %.0f %.0f)\n",
        a.id==100?'A':'B',double(a.time),clock.stage,double(h.x),double(h.y),double(h.z),double(ht.x),double(ht.y),double(ht.z),
        double(b1.x),double(b1.y),double(b1.z),double(c.x),double(c.y),double(c.z),double(t1.x),double(t1.y),double(t1.z));
      }
    if(!hasPrev) { pA = b0; pB = b1; hasPrev = true; return; }
    if(foe!=nullptr && fb.striking() && fb.strikeId()!=spent && foe->a.r) {
      Vec3 f0, f1; foe->blade(f0,f1);
      Vec3 fp0 = foe->hasPrev ? foe->pA : f0, fp1 = foe->hasPrev ? foe->pB : f1;
      const int N = 4;
      for(int s=1; s<=N && fb.strikeId()!=spent; ++s) {
        const float u0 = float(s-1)/N, u1 = float(s)/N;
        const Vec3 m0 = pA+(b0-pA)*u1, m1 = pB+(b1-pB)*u1;
        const Vec3 o0 = fp0+(f0-fp0)*u1, o1 = fp1+(f1-fp1)*u1;
        Vec3 at;
        const float r = weapon==FightBody::W_Fist ? 5.f : 3.f;
        const float rr= foe->weapon==FightBody::W_Fist ? 5.f : 3.f;
        if(segSegDist(m0,m1,o0,o1,&at)<r+rr+1.f) {
          // блок: клинок о клинок
          const float dts = float(a.dt)/1000.f;
          const Vec3 v = ((b0+b1)*0.5f-(pA+pB)*0.5f)/dts*0.01f; // м/с
          if(a.r)   a.r->nudge(org(a.disp[rig->handBone(1)]), v*-0.7f);
          foe->a.r->nudge(org(foe->a.disp[foe->rig->handBone(1)]), v*0.35f);
          spent = fb.strikeId();
          blocks++;
          fx.push_back({at,'y'});
          char buf[96]; std::snprintf(buf,sizeof(buf),"t=%.2f block %.1f m/s",double(a.time),double(v.length()));
          log.push_back(buf);
          break;
          }
        for(int j=0; j<=5; ++j) {
          const float k = 0.15f + 0.85f*float(j)/5.f;
          const Vec3 q0 = (pA+(pB-pA)*k)*(1.f-u0) + (b0+(b1-b0)*k)*u0;
          const Vec3 q1 = (pA+(pB-pA)*k)*(1.f-u1) + (b0+(b1-b0)*k)*u1;
          Ragdoll::RayHit rh;
          if(!Ragdoll::rayTest(foe->a.sk, foe->a.disp.data(), foe->a.disp.size(), q0, q1+(q1-q0)*0.2f, rh))
            continue;
          const float dts = float(a.dt)/1000.f;
          const Vec3  v   = (q1-q0)*float(N)/dts; // см/с
          if(v.length()<400.f)
            continue; // медленный клинок не ранит
          const float pw  = FightBody::hitPower(weapon, v.length());
          static const char* zn[] = {"body","head","legs","arms"};
          const float mul = rh.zone==1 ? 2.f : (rh.zone==2 ? 0.75f : (rh.zone==3 ? 0.5f : 1.f));
          foe->a.hit(rh.at, Vec3::normalize(v), pw, dmg*mul);
          if(a.r) a.r->nudge(rh.at, v*-0.0035f);
          spent = fb.strikeId();
          hits++;
          fx.push_back({rh.at,'r'});
          char buf[128]; std::snprintf(buf,sizeof(buf),"t=%.2f hit %s x%.2f %.1f m/s power %.1f",double(a.time),zn[rh.zone<4?rh.zone:0],
                                       double(mul),double(v.length()/100.f),double(pw));
          log.push_back(buf);
          break;
          }
        }
      }
    pA = b0; pB = b1;
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
