// Gothic II Mobile: стенд - настоящий скелет человека Gothic II.
//
// Источник - стойка S_FISTRUN людей из тестовых данных библиотеки ZenKit (MIT; её скачивает
// tools/fetch-opengothic.sh вместе с движком: third_party/opengothic/lib/ZenKit/tests/samples/G2/
// HUMANS-S_FISTRUN.MAN). В файле - кадры анимации: для каждой кости перенос и поворот относительно родителя. Из них
// стенд берёт то, чего нет в самодельном скелете: настоящие длины костей, оси костей (как у Biped: X - вдоль кости,
// локоть и колено гнутся вокруг Y в сторону -Z), порядок и подчинение костей (бёдра - под тазом, ключицы - под
// шеей), левую тройку модели (левые кости - по -X при «вперёд» +Z) и саму стойку (кадры анимации игры). В репозитории
// этих данных нет - стенд читает файл при запуске.
//
// Позы привязки (HUMANS.MDH) в тестовых данных нет: стенд строит её по правилам Biped в нескольких вариантах (руки в
// стороны, под 45°, вниз) - код позы и куклы не должен от неё зависеть (./ragtest bind).
#pragma once

#include "graphics/mesh/skeleton.h"
#include "graphics/mesh/animmath.h"
#include "graphics/mesh/limbik.h"

#include <zenkit/ModelAnimation.hh>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

namespace gothic {

using Tempest::Matrix4x4;
using Tempest::Vec3;

// Кости HUMANS.MDH по порядку номеров анимации (кости без кадров - слоты ZS_: их места в игре задаёт модель)
struct NodeDef { const char* name; int parent; };
inline const NodeDef* humanNodes(size_t& n) {
  static const NodeDef nodes[] = {
    {"BIP01",-1},            {"BIP01 PELVIS",0},      {"BIP01 SPINE",1},       {"BIP01 SPINE1",2},
    {"BIP01 SPINE2",3},      {"BIP01 NECK",4},        {"BIP01 HEAD",5},        {"ZS_HELMET",6},
    {"BIP01 L CLAVICLE",5},  {"BIP01 L UPPERARM",8},  {"BIP01 L FOREARM",9},   {"BIP01 L HAND",10},
    {"BIP01 L FINGER0",11},  {"ZS_LEFTHAND",11},      {"ZS_LEFTARM",10},
    {"BIP01 R CLAVICLE",5},  {"BIP01 R UPPERARM",15}, {"BIP01 R FOREARM",16},  {"BIP01 R HAND",17},
    {"BIP01 R FINGER0",18},  {"ZS_RIGHTHAND",18},     {"ZS_RIGHTARM",17},
    {"ZS_BOW",4},            {"ZS_CROSSBOW",4},       {"ZS_LONGSWORD",4},      {"ZS_SWORD",1},
    {"BIP01 L THIGH",1},     {"BIP01 L CALF",26},     {"BIP01 L FOOT",27},     {"BIP01 L TOE0",28},
    {"BIP01 R THIGH",1},     {"BIP01 R CALF",30},     {"BIP01 R FOOT",31},     {"BIP01 R TOE0",32},
    };
  n = sizeof(nodes)/sizeof(nodes[0]);
  return nodes;
  }

struct ManAnim final {
  std::string                          name;
  uint32_t                             frames = 0, nodes = 0;
  float                                fps    = 10;
  std::vector<uint32_t>                idx;
  std::vector<zenkit::AnimationSample> samples;   // frames * nodes

  const zenkit::AnimationSample& at(uint32_t frame, uint32_t k) const { return samples[frame*nodes+k]; }
  };

// Файл анимации Gothic (.MAN) - как zenkit::ModelAnimation::load, без самой библиотеки
inline bool loadMan(const std::string& path, ManAnim& out) {
  FILE* f = std::fopen(path.c_str(),"rb");
  if(f==nullptr)
    return false;
  std::vector<uint8_t> d;
  uint8_t buf[4096];
  for(size_t n; (n=std::fread(buf,1,sizeof(buf),f))>0; )
    d.insert(d.end(),buf,buf+n);
  std::fclose(f);
  auto u16 = [&](size_t p) { return uint16_t(d[p] | (d[p+1]<<8)); };
  auto u32 = [&](size_t p) { return uint32_t(d[p]) | (uint32_t(d[p+1])<<8) | (uint32_t(d[p+2])<<16) | (uint32_t(d[p+3])<<24); };
  auto f32 = [&](size_t p) { float v; const uint32_t x = u32(p); std::memcpy(&v,&x,4); return v; };
  float pmin = 0, pscale = 1;
  for(size_t p=0; p+6<=d.size(); ) {
    const uint16_t type = u16(p);
    const uint32_t len  = u32(p+2);
    const size_t   c    = p+6;
    if(c+len>d.size())
      return false;
    if(type==0xa020) {
      size_t q = c+2;
      while(q<c+len && d[q]!='\n')
        out.name.push_back(char(d[q++]));
      ++q;
      out.frames = u32(q+4);
      out.nodes  = u32(q+8);
      out.fps    = f32(q+12);
      pmin       = f32(q+20);
      pscale     = f32(q+24);
      }
    if(type==0xa090) {
      size_t q = c+4;
      out.idx.resize(out.nodes);
      for(auto& i:out.idx) {
        i = u32(q);
        q += 4;
        }
      out.samples.resize(size_t(out.nodes)*out.frames);
      const float mid = float((1<<15)-1), sc = (1.f/(float(1<<16)-1.f))*2.1f;
      for(auto& s:out.samples) {
        float x = (float(u16(q))-mid)*sc, y = (float(u16(q+2))-mid)*sc, z = (float(u16(q+4))-mid)*sc, w = 0;
        const float l = x*x+y*y+z*z;
        if(l>1.f) {
          const float k = 1.f/std::sqrt(l);
          x *= k; y *= k; z *= k;
          } else {
          w = std::sqrt(1.f-l);
          }
        s.rotation = zenkit::Quat(w,x,y,z);
        s.position = zenkit::Vec3{float(u16(q+6))*pscale+pmin, float(u16(q+8))*pscale+pmin, float(u16(q+10))*pscale+pmin};
        q += 12;
        }
      }
    p = c+len;
    }
  return out.nodes>0 && out.frames>0 && out.samples.size()==size_t(out.nodes)*out.frames;
  }

inline std::string samplePath() {
  if(const char* e = std::getenv("G2_FISTRUN"))
    return e;
#ifdef ZENKIT_SAMPLES
  return std::string(ZENKIT_SAMPLES) + "/G2/HUMANS-S_FISTRUN.MAN";
#else
  return "third_party/opengothic/lib/ZenKit/tests/samples/G2/HUMANS-S_FISTRUN.MAN";
#endif
  }

inline const ManAnim& fistRun() {
  static ManAnim a;
  static bool    init = false;
  if(!init) {
    init = true;
    if(!loadMan(samplePath(),a)) {
      std::fprintf(stderr,"нет данных скелета: %s (tools/fetch-opengothic.sh)\n",samplePath().c_str());
      std::exit(2);
      }
    }
  return a;
  }

inline Matrix4x4 frame(const Vec3& at, const Vec3& x, const Vec3& y, const Vec3& z) {
  Matrix4x4 m = Matrix4x4::mkIdentity();
  m.set(0,0,x.x); m.set(0,1,x.y); m.set(0,2,x.z);
  m.set(1,0,y.x); m.set(1,1,y.y); m.set(1,2,y.z);
  m.set(2,0,z.x); m.set(2,1,z.y); m.set(2,2,z.z);
  m.set(3,0,at.x); m.set(3,1,at.y); m.set(3,2,at.z);
  return m;
  }

inline Vec3 xform(const Matrix4x4& m, const Vec3& v) {
  return LimbIk::origin(m) + LimbIk::axis(m,0)*v.x + LimbIk::axis(m,1)*v.y + LimbIk::axis(m,2)*v.z;
  }

// Поза привязки по правилам Biped; armDown - на сколько градусов руки опущены от горизонтали (0 - T-поза, 45 -
// A-поза, 90 - вниз вдоль тела)
enum Bind : int { B_T = 0, B_A = 45, B_Down = 85 };

inline int bindFromEnv() {
  const char* e = std::getenv("BIND");
  if(e==nullptr)
    return B_T;
  if(e[0]=='A' || e[0]=='a')
    return B_A;
  if(e[0]=='D' || e[0]=='d')
    return B_Down;
  if(e[0]>='0' && e[0]<='9')
    return std::atoi(e);
  return B_T;
  }

// Мировые матрицы костей в позе привязки (система модели: вверх +Y, вперёд +Z, левые кости - по -X, как у Gothic)
inline std::vector<Matrix4x4> bindPose(int armDown) {
  const ManAnim& a = fistRun();
  size_t n = 0;
  const NodeDef* nd = humanNodes(n);
  // переносы костей относительно родителя - из кадра 0 (у всех, кроме корня и таза, они постоянны)
  std::vector<Vec3> off(n);
  std::vector<bool> has(n,false);
  for(uint32_t k=0; k<a.nodes; ++k) {
    const auto& s = a.at(0,k);
    if(a.idx[k]<n) {
      off[a.idx[k]] = Vec3(s.position.x,s.position.y,s.position.z);
      has[a.idx[k]] = true;
      }
    }
  // ключицы жёстко стоят на груди (SPINE2), хотя в скелете подчинены шее: их место - из кадра анимации в системе
  // груди (шея в позе привязки прямая)
  Vec3 clav[2];
  {
  std::vector<Matrix4x4> w(n,Matrix4x4::mkIdentity());
  for(uint32_t k=0; k<a.nodes; ++k) {
    const size_t i = a.idx[k];
    const Matrix4x4 l = mkMatrix(a.at(0,k));
    w[i] = nd[i].parent<0 ? l : w[size_t(nd[i].parent)]*l;
    }
  for(int s=0; s<2; ++s) {
    Matrix4x4 inv = w[4];
    inv.inverse();
    Vec3 p = LimbIk::origin(w[s==0 ? 8 : 15]);
    inv.project(p);
    clav[s] = p;
    }
  }
  const float al = float(armDown)*float(M_PI)/180.f;
  const float ca = std::cos(al), sa = std::sin(al);
  const Vec3 up(0,1,0), left(-1,0,0), fwd(0,0,1), down(0,-1,0), right(1,0,0), back(0,0,-1);
  // рука: X - вдоль кости, Z - назад (локоть гнётся к -Z, вперёд), Y = Z x X
  const Vec3 lx = left*ca + down*sa, rx = right*ca + down*sa;
  const Vec3 ly = Vec3::crossProduct(back,lx), ry = Vec3::crossProduct(back,rx);
  std::vector<Matrix4x4> w(n);
  // стоя на прямых ногах: носки на высоте 0,32 см, как в анимации игры
  const float pelY = 0.32f + 12.63f + 44.95f + 40.92f;
  for(size_t i=0; i<n; ++i) {
    const std::string nm = nd[i].name;
    const int         p  = nd[i].parent;
    Vec3 x = up, y = left, z = fwd; // корпус, шея, голова
    if(i==0)                        { x = fwd;   y = up;    z = left; }
    else if(nm.find("L CLAV")!=std::string::npos) { x = left;  y = up;    z = back; }
    else if(nm.find("R CLAV")!=std::string::npos) { x = right; y = down;  z = back; }
    else if(nm.find(" L UPPER")!=std::string::npos || nm.find(" L FORE")!=std::string::npos ||
            nm.find(" L HAND")!=std::string::npos  || nm.find(" L FINGER")!=std::string::npos ||
            nm=="ZS_LEFTHAND" || nm=="ZS_LEFTARM")   { x = lx; y = ly; z = back; }
    else if(nm.find(" R UPPER")!=std::string::npos || nm.find(" R FORE")!=std::string::npos ||
            nm.find(" R HAND")!=std::string::npos  || nm.find(" R FINGER")!=std::string::npos ||
            nm=="ZS_RIGHTHAND" || nm=="ZS_RIGHTARM") { x = rx; y = ry; z = back; }
    else if(nm.find("TOE")!=std::string::npos)     { x = fwd;  y = right; z = up; }
    else if(nm.find("THIGH")!=std::string::npos || nm.find("CALF")!=std::string::npos || nm.find("FOOT")!=std::string::npos)
                                                   { x = down; y = right; z = fwd; }
    Vec3 at;
    if(p<0) {
      at = Vec3(0,pelY,0) - (fwd*off[1].x + up*off[1].y + left*off[1].z); // таз - над началом координат
      }
    else if(nm.find("CLAVICLE")!=std::string::npos) {
      at = xform(w[4], clav[nm.find(" L ")!=std::string::npos ? 0 : 1]);
      }
    else if(has[i]) {
      at = xform(w[size_t(p)], off[i]);
      }
    else {
      // слоты: шлем - над головой; кисть - в ладони (8,5 см от запястья, 3 см к ладони); щит - на предплечье; лук,
      // арбалет и двуручник - на спине, меч - у левого бедра
      Vec3 o(0,0,0);
      if(nm=="ZS_HELMET")         o = Vec3(12,0,2);
      else if(nm=="ZS_LEFTHAND")  o = Vec3(8.5f,-3,0);
      else if(nm=="ZS_RIGHTHAND") o = Vec3(8.5f, 3,0);
      else if(nm=="ZS_LEFTARM" || nm=="ZS_RIGHTARM") o = Vec3(14,0,0);
      else if(nm=="ZS_BOW" || nm=="ZS_CROSSBOW")     o = Vec3(14,-4,-13);
      else if(nm=="ZS_LONGSWORD") o = Vec3(18,5,-13);
      else if(nm=="ZS_SWORD")     o = Vec3(-2,14,3);
      at = xform(w[size_t(p)], o);
      }
    w[i] = frame(at,x,y,z);
    }
  return w;
  }

// Скелеты живут до конца: схемы куклы, BodyRig и InjuryRig кэшируются по адресу скелета
inline std::shared_ptr<Skeleton> human(int armDown = bindFromEnv(), const char* name = "HUMANS.MDH") {
  static std::vector<std::shared_ptr<Skeleton>> alive;
  size_t n = 0;
  const NodeDef* nd = humanNodes(n);
  zenkit::ModelHierarchy h;
  auto sk = std::make_shared<Skeleton>(h,nullptr,name);
  alive.push_back(sk);
  sk->nodes.resize(n);
  sk->tr = bindPose(armDown);
  for(size_t i=0; i<n; ++i) {
    sk->nodes[i].name   = nd[i].name;
    sk->nodes[i].parent = nd[i].parent<0 ? size_t(-1) : size_t(nd[i].parent);
    if(nd[i].parent<0) {
      sk->nodes[i].tr = sk->tr[i];
      sk->rootNodes.push_back(i);
      } else {
      sk->nodes[i].tr = LimbIk::relative(sk->tr[size_t(nd[i].parent)], sk->tr[i]);
      }
    }
  for(size_t i=0; i<n; ++i)
    if(sk->nodes[i].name=="BIP01 HEAD")
      sk->BIP01_HEAD = i;
  return sk;
  }

// Кадр анимации игры S_FISTRUN на скелете: матрицы костей в системе модели (корень - над началом координат, как
// ставит Pose: перенос корня по горизонтали убран). t - с, анимация зациклена
inline void fistRunPose(const Skeleton& sk, float t, std::vector<Matrix4x4>& out) {
  const ManAnim& a = fistRun();
  size_t n = 0;
  const NodeDef* nd = humanNodes(n);
  const float    ft = t*a.fps;
  const uint32_t f0 = uint32_t(std::floor(ft))%a.frames, f1 = (f0+1)%a.frames;
  const float    u  = ft-std::floor(ft);
  std::vector<Matrix4x4> local(n);
  for(size_t i=0; i<n; ++i)
    local[i] = sk.nodes[i].tr;
  for(uint32_t k=0; k<a.nodes; ++k) {
    const size_t i = a.idx[k];
    if(i>=n)
      continue;
    auto s = mix(a.at(f0,k), a.at(f1,k), u);
    if(i==0) {
      s.position.x = 0;
      s.position.z = 0;
      }
    local[i] = mkMatrix(s);
    }
  out.resize(n);
  for(size_t i=0; i<n; ++i)
    out[i] = nd[i].parent<0 ? local[i] : out[size_t(nd[i].parent)]*local[i];
  }

}
