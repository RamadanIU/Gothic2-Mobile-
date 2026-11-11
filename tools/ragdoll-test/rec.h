// Gothic II Mobile: площадка - запись сцены стенда для 3D-просмотра (viewer/lab.html, pack.py).
// Кадр - точки тела каждого человека (суставы, макушка и нос головы, ладони), клинки, отрезки и точки отладки;
// события - попадания и блоки (где, чем, по чему, урон), заметки. Формат - JSON, координаты в мм (целые).
#pragma once

#include "actor.h"

#include <cstdio>
#include <string>
#include <vector>

namespace bench {

struct Recorder {
  // точки тела в кадре (viewer/lab.html, JOINTS - в том же порядке)
  enum : int { NPoints = 26 };

  struct Event { float t; std::string kind; Vec3 at; std::string text; int actor; };
  struct Line  { Vec3 a, b; char color; };
  struct Dot   { Vec3 at; char color; float r; };
  struct Box   { Vec3 c, h; };
  struct Frame {
    float                           t = 0;
    std::string                     label;
    std::vector<std::vector<int>>   body;    // по людям: NPoints*3 чисел, мм
    std::vector<std::vector<int>>   ghost;   // цель мышц (может не быть)
    std::vector<std::vector<int>>   blade;   // [человек, ax,ay,az, bx,by,bz]
    std::vector<Line>               lines;
    std::vector<Dot>                dots;
    };

  std::string              name, title, note;
  std::vector<std::string> actors;
  std::vector<Frame>       frames;
  std::vector<Event>       events;
  std::vector<Box>         boxes;
  bool                     on = false;

  void begin(const std::string& nm, const std::string& ttl, const std::vector<std::string>& who) {
    name   = nm;
    title  = ttl;
    actors = who;
    frames.clear();
    events.clear();
    boxes.clear();
    on = true;
    }

  void frame(float t, const std::string& label) {
    if(!on)
      return;
    Frame f;
    f.t     = t;
    f.label = label;
    frames.push_back(std::move(f));
    }

  static void push(std::vector<int>& out, const Vec3& v) {
    out.push_back(int(std::lround(v.x*10.f)));
    out.push_back(int(std::lround(v.y*10.f)));
    out.push_back(int(std::lround(v.z*10.f)));
    }

  // точки тела по позе (мировые матрицы всех костей)
  static std::vector<int> points(const Skeleton& sk, const std::vector<Matrix4x4>& w) {
    static const char* names[] = {
      "BIP01 PELVIS","BIP01 SPINE","BIP01 SPINE1","BIP01 SPINE2","BIP01 NECK","BIP01 HEAD",
      "BIP01 L UPPERARM","BIP01 L FOREARM","BIP01 L HAND","BIP01 L FINGER0",
      "BIP01 R UPPERARM","BIP01 R FOREARM","BIP01 R HAND","BIP01 R FINGER0",
      "BIP01 L THIGH","BIP01 L CALF","BIP01 L FOOT","BIP01 L TOE0",
      "BIP01 R THIGH","BIP01 R CALF","BIP01 R FOOT","BIP01 R TOE0",
      };
    std::vector<int> out;
    out.reserve(NPoints*3);
    Vec3 last;
    for(auto nm:names) {
      size_t b = bone(sk,nm);
      if(b==size_t(-1) && std::string(nm).find("FINGER0")!=std::string::npos) {
        // нет пальца - кончик кисти: 13 см от запястья по предплечью
        const std::string side = std::string(nm).find(" L ")!=std::string::npos ? "BIP01 L " : "BIP01 R ";
        const size_t h = bone(sk,(side+"HAND").c_str()), f = bone(sk,(side+"FOREARM").c_str());
        if(h!=size_t(-1) && f!=size_t(-1)) {
          const Vec3 d = org(w[h])-org(w[f]);
          last = org(w[h]) + (d.length()>1e-3f ? Vec3::normalize(d)*13.f : Vec3());
          }
        push(out,last);
        continue;
        }
      if(b!=size_t(-1))
        last = org(w[b]);
      push(out,last);
      }
    // голова: макушка и нос (по осям кости головы Biped: X - вверх, Z - вперёд); нет - по шее
    {
    const size_t h = bone(sk,"BIP01 HEAD");
    Vec3 top = last, nose = last;
    if(h!=size_t(-1)) {
      const Vec3 o = org(w[h]);
      Vec3 up = LimbIk::axis(w[h],0), fw = LimbIk::axis(w[h],2);
      up = up.length()>1e-4f ? Vec3::normalize(up) : Vec3(0,1,0);
      fw = fw.length()>1e-4f ? Vec3::normalize(fw) : Vec3(0,0,1);
      top  = o + up*21.f;
      nose = o + up*9.f + fw*11.5f;
      }
    push(out,top);
    push(out,nose);
    }
    // ладони: точка в 4 см от кисти по нормали ладони (у Biped: левая -Y, правая +Y)
    for(int s=0; s<2; ++s) {
      const size_t h = bone(sk, s==0 ? "BIP01 L HAND" : "BIP01 R HAND");
      Vec3 p = last;
      if(h!=size_t(-1)) {
        Vec3 n = LimbIk::axis(w[h],1), x = LimbIk::axis(w[h],0);
        n = n.length()>1e-4f ? Vec3::normalize(n) : Vec3(0,-1,0);
        x = x.length()>1e-4f ? Vec3::normalize(x) : Vec3(1,0,0);
        p = org(w[h]) + x*6.f + n*(s==0 ? -4.f : 4.f);
        }
      push(out,p);
      }
    return out;
    }

  void body(const Actor& a) {
    if(on && !frames.empty())
      frames.back().body.push_back(points(a.sk,a.disp));
    }
  void ghost(const Skeleton& sk, const std::vector<Matrix4x4>& w) {
    if(on && !frames.empty())
      frames.back().ghost.push_back(points(sk,w));
    }
  void blade(int actor, const Vec3& a, const Vec3& b) {
    if(!on || frames.empty())
      return;
    std::vector<int> v;
    v.push_back(actor);
    push(v,a);
    push(v,b);
    frames.back().blade.push_back(std::move(v));
    }
  void line(const Vec3& a, const Vec3& b, char color) {
    if(on && !frames.empty())
      frames.back().lines.push_back({a,b,color});
    }
  void dot(const Vec3& at, char color, float r = 3.f) {
    if(on && !frames.empty())
      frames.back().dots.push_back({at,color,r});
    }
  void event(float t, const std::string& kind, const Vec3& at, const std::string& text, int actor = -1) {
    if(on)
      events.push_back({t,kind,at,text,actor});
    }
  void box(const Vec3& c, const Vec3& h) {
    if(on)
      boxes.push_back({c,h});
    }

  static std::string esc(const std::string& s) {
    std::string r;
    for(char c:s) {
      if(c=='"' || c=='\\') { r.push_back('\\'); r.push_back(c); }
      else if(c=='\n')      { r += "\\n"; }
      else                  { r.push_back(c); }
      }
    return r;
    }

  bool save(const std::string& path) const {
    FILE* f = std::fopen(path.c_str(),"w");
    if(f==nullptr)
      return false;
    auto v3 = [&](const Vec3& v) {
      std::fprintf(f,"%d,%d,%d",int(std::lround(v.x*10.f)),int(std::lround(v.y*10.f)),int(std::lround(v.z*10.f)));
      };
    auto ints = [&](const std::vector<int>& v) {
      std::fputc('[',f);
      for(size_t i=0; i<v.size(); ++i)
        std::fprintf(f,i ? ",%d" : "%d",v[i]);
      std::fputc(']',f);
      };
    std::fprintf(f,"{\"name\":\"%s\",\"title\":\"%s\",\"note\":\"%s\",\"actors\":[",esc(name).c_str(),esc(title).c_str(),esc(note).c_str());
    for(size_t i=0; i<actors.size(); ++i)
      std::fprintf(f,"%s\"%s\"",i ? "," : "",esc(actors[i]).c_str());
    std::fprintf(f,"],\"boxes\":[");
    for(size_t i=0; i<boxes.size(); ++i) {
      std::fprintf(f,"%s[",i ? "," : "");
      v3(boxes[i].c); std::fputc(',',f); v3(boxes[i].h);
      std::fputc(']',f);
      }
    std::fprintf(f,"],\"events\":[");
    for(size_t i=0; i<events.size(); ++i) {
      const auto& e = events[i];
      std::fprintf(f,"%s{\"t\":%.3f,\"k\":\"%s\",\"a\":%d,\"p\":[",i ? "," : "",double(e.t),esc(e.kind).c_str(),e.actor);
      v3(e.at);
      std::fprintf(f,"],\"s\":\"%s\"}",esc(e.text).c_str());
      }
    std::fprintf(f,"],\"frames\":[\n");
    for(size_t i=0; i<frames.size(); ++i) {
      const auto& fr = frames[i];
      std::fprintf(f,"%s{\"t\":%.3f,\"l\":\"%s\",\"b\":[",i ? ",\n" : "",double(fr.t),esc(fr.label).c_str());
      for(size_t k=0; k<fr.body.size(); ++k) {
        if(k) std::fputc(',',f);
        ints(fr.body[k]);
        }
      std::fprintf(f,"]");
      if(!fr.ghost.empty()) {
        std::fprintf(f,",\"g\":[");
        for(size_t k=0; k<fr.ghost.size(); ++k) {
          if(k) std::fputc(',',f);
          ints(fr.ghost[k]);
          }
        std::fprintf(f,"]");
        }
      if(!fr.blade.empty()) {
        std::fprintf(f,",\"w\":[");
        for(size_t k=0; k<fr.blade.size(); ++k) {
          if(k) std::fputc(',',f);
          ints(fr.blade[k]);
          }
        std::fprintf(f,"]");
        }
      if(!fr.lines.empty()) {
        std::fprintf(f,",\"ln\":[");
        for(size_t k=0; k<fr.lines.size(); ++k) {
          std::fprintf(f,"%s[",k ? "," : "");
          v3(fr.lines[k].a); std::fputc(',',f); v3(fr.lines[k].b);
          std::fprintf(f,",\"%c\"]",fr.lines[k].color);
          }
        std::fprintf(f,"]");
        }
      if(!fr.dots.empty()) {
        std::fprintf(f,",\"d\":[");
        for(size_t k=0; k<fr.dots.size(); ++k) {
          std::fprintf(f,"%s[",k ? "," : "");
          v3(fr.dots[k].at);
          std::fprintf(f,",\"%c\",%d]",fr.dots[k].color,int(std::lround(fr.dots[k].r*10.f)));
          }
        std::fprintf(f,"]");
        }
      std::fputc('}',f);
      }
    std::fprintf(f,"\n]}\n");
    std::fclose(f);
    return true;
    }
  };

}
