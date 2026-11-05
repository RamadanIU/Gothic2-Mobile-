// Gothic II Mobile: стенд режима стратегии (docs/RTS.md, «Как проверяем»). Настоящее ядро common/game/rts
// (каталог, экономика, стройка, найм, герои, захват, отряды, ИИ сторон, дипломатия, сохранение) в упрощённом
// мире: бойцы ходят по прямой, бьют ближайшего врага по силе своего ранга. Бой в игре ведёт боевой ИИ игры -
// здесь проверяются решения сторон, а не фехтование. Запуск: tools/rts-test/build.sh, затем
//   rtstest            - проверки, в конце «ALL OK»
//   rtstest sweep [N]  - N партий ИИ против ИИ: сводка побед, городов, мест, героев
//   rtstest game SEED  - одна партия с журналом донесений

#include "game/rts/rtscore.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <set>
#include <string>
#include <vector>

using namespace rts;

static int fails = 0;
#define CHECK(c, ...) do { if(!(c)) { ++fails; std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } } while(0)

// ---- упрощённый мир ----

struct Mob {
  uint32_t id = 0;
  Faction  f  = F_None;
  V3       pos;
  V3       goal;
  bool     moving = false;
  bool     run    = false;
  bool     alive  = true;
  bool     down   = false;
  bool     hidden = false;
  bool     calm   = false;
  bool     hero   = false;
  float    hp = 100, hpMax = 100;
  float    dps = 10;
  float    range = 200;
  float    speed = 300;
  uint32_t target = 0;
  uint32_t lastHit = 0;
  uint64_t downAt = 0;
  };

struct Headless : WorldApi {
  Game* game = nullptr;
  std::map<uint32_t,Mob> bodies;
  std::vector<Event> log;
  bool verbose = false;
  uint64_t now = 0;
  uint32_t lotSeed = 7;
  std::map<std::pair<int,int>,Treaty> treaties;
  int treatyCalls = 0;
  int heroLevels = 0;
  int revived = 0;
  std::map<uint32_t,int> moveCalls;   // сколько раз бойцу отдан приказ идти
  std::map<uint32_t,int> settledNow;  // расселён (1) или нет
  int settleCalls = 0, unsettleCalls = 0;

  void fill(Mob& b, float power, bool ranged) {
    b.hpMax = 80.f + 70.f*power;
    b.hp    = b.hpMax;
    b.dps   = 4.f + 5.f*power;
    b.range = ranged ? 1800.f : 220.f;
    }

  bool spawnUnit(Unit& u, const Loadout& l, const V3& at) override {
    Mob b;
    b.id  = u.id;
    b.f   = u.f;
    b.pos = at;
    b.goal = at;
    auto& d = unitDef(size_t(u.def));
    fill(b,d.power,l.rangedMain || d.role==Role::Caster);
    b.speed = d.role==Role::Beast ? 500.f : 320.f;
    bodies[u.id] = b;
    u.pos = at;
    return true;
    }
  bool spawnHero(Unit& u, const HeroDef& d, const Hero& h, const V3& at) override {
    Mob b;
    b.id  = u.id;
    b.f   = u.f;
    b.pos = at;
    b.goal = at;
    b.hero = true;
    fill(b,heroPower(d,h.level),d.ranged!=nullptr);
    bodies[u.id] = b;
    u.pos = at;
    return true;
    }
  bool query(const Unit& u, UnitInfo& out) override {
    auto it = bodies.find(u.id);
    if(it==bodies.end())
      return false;
    auto& b = it->second;
    if(b.hidden)
      return false;
    out.pos   = b.pos;
    out.alive = b.alive;
    out.down  = b.down;
    out.fighting = b.target!=0;
    out.hp01  = b.hp/b.hpMax;
    return true;
    }
  void moveTo(const Unit& u, const V3& to, bool run) override {
    moveCalls[u.id]++;
    auto& b = bodies[u.id];
    b.goal = to;
    b.moving = true;
    b.run = run;
    b.target = 0;
    }
  void attack(const Unit& u, const Unit& t) override {
    auto& b = bodies[u.id];
    if(b.calm)
      return;
    b.target = t.id;
    }
  void halt(const Unit& u) override {
    bodies[u.id].moving = false;
    }
  void setCalm(const Unit& u, bool calm) override {
    bodies[u.id].calm = calm;
    if(calm)
      bodies[u.id].target = 0;
    }
  void hideUnit(const Unit& u) override {
    bodies[u.id].hidden = true;
    }
  void reviveHero(Unit& u, const Hero& h, const V3& at) override {
    auto& b = bodies[u.id];
    b.hidden = false;
    b.alive  = true;
    b.down   = false;
    fill(b,heroPower(heroDef(size_t(h.def)),h.level),heroDef(size_t(h.def)).ranged!=nullptr);
    b.pos = at;
    b.goal = at;
    b.target = 0;
    ++revived;
    }
  void heroStats(const Unit& u, const Hero& h) override {
    auto& b = bodies[u.id];
    const float hp01 = b.hp/b.hpMax;
    fill(b,heroPower(heroDef(size_t(h.def)),h.level),heroDef(size_t(h.def)).ranged!=nullptr);
    b.hp = b.hpMax*hp01;
    ++heroLevels;
    }
  void treatyChanged(Faction a, Faction b, Treaty t) override {
    treaties[{std::min(int(a),int(b)),std::max(int(a),int(b))}] = t;
    ++treatyCalls;
    }
  void buildingChanged(const Building&) override {}
  void settle(const std::vector<const Unit*>& units, const V3& c, float r) override {
    // Как в игре: каждый - на своё место у центра (костёр, скамья), дальше его ведёт распорядок
    ++settleCalls;
    uint32_t k = 0;
    for(auto* u:units) {
      settledNow[u->id] = 1;
      auto& b = bodies[u->id];
      const float a = float(k++)*2.3999632f;
      b.goal   = c + V3(std::cos(a)*r*0.7f,0,std::sin(a)*r*0.7f);
      b.moving = true;
      b.run    = false;
      }
    }
  void unsettle(const Unit& u) override {
    ++unsettleCalls;
    settledNow[u.id] = 0;
    bodies[u.id].moving = false;
    }
  float lotMaxRadius = 1e9f; // тесно, как в верхнем квартале: больше этого места нет
  int   lotFails = 0;
  bool findLot(const V3& c, float minR, float maxR, float radius, uint32_t seed, V3& out) override {
    if(radius>lotMaxRadius) {
      ++lotFails;
      return false;
    }
    lotSeed = lotSeed*1103515245u + seed + 12345u;
    const float a = float(lotSeed%3600)/3600.f*6.2831853f;
    const float r = minR + float((lotSeed/3600)%1000)/1000.f*(maxR-minR);
    out = c + V3(std::cos(a)*r,0,std::sin(a)*r);
    return true;
    }
  bool route(const V3& from, const V3& to, std::vector<V3>& out) override {
    // Прямая с изломом: проверка, что строй идёт по точкам
    out.clear();
    out.push_back((from+to)*0.5f + V3(500,0,-500));
    out.push_back(to);
    return true;
    }
  void report(const Event& e) override {
    log.push_back(e);
    if(verbose)
      std::printf("[%5.1f мин] %s%s\n",double(e.at)/60000.0,e.f<F_Count ? (std::string("(")+factionName(e.f)+") ").c_str() : "",e.text.c_str());
    }

  bool hostile(Faction a, Faction b) const { return game->hostile(a,b); }

  // Шаг мира: движение и бой
  void step(uint64_t dt) {
    now += dt;
    const float s = float(dt)/1000.f;
    // ближайший враг для стоящих без цели: бойцы игры сами бьют врагов рядом (перцепции)
    for(auto& [id,b]:bodies) {
      if(!b.alive || b.down || b.hidden || b.calm)
        continue;
      if(b.target!=0) {
        auto it = bodies.find(b.target);
        if(it==bodies.end() || !it->second.alive || it->second.down || it->second.hidden)
          b.target = 0;
        }
      if(b.target==0) {
        float bd = 900.f*900.f;
        for(auto& [oid,o]:bodies) {
          if(!o.alive || o.down || o.hidden || !hostile(b.f,o.f))
            continue;
          const float d = qDistXZ(o.pos,b.pos);
          if(d<bd) {
            bd = d;
            b.target = oid;
            }
          }
        }
      }
    for(auto& [id,b]:bodies) {
      if(!b.alive || b.down || b.hidden)
        continue;
      V3 to = b.goal;
      bool go = b.moving;
      if(b.target!=0) {
        auto& t = bodies[b.target];
        if(distXZ(t.pos,b.pos)<=b.range) {
          go = false;
          t.hp -= b.dps*s;
          t.lastHit = id;
          } else {
          to = t.pos;
          go = true;
          }
        }
      if(go) {
        V3 d = to-b.pos;
        const float l = d.lenXZ();
        const float v = (b.run || b.target!=0 ? b.speed : b.speed*0.5f)*s;
        if(l<=v) {
          b.pos = to;
          if(b.target==0)
            b.moving = false;
          } else {
          b.pos = b.pos + V3(d.x/l*v,0,d.z/l*v);
          }
        }
      }
    for(auto& [id,b]:bodies) {
      if(!b.alive || b.down || b.hidden || b.hp>0)
        continue;
      if(b.hero) {
        b.down = true;
        b.target = 0;
        b.hp = 1;
        game->onDown(id,b.lastHit);
        } else {
        b.alive = false;
        b.target = 0;
        game->onKill(b.lastHit,id);
        }
      }
    }
  };

static void setupGame(Game& g, Headless& w, const Setup& s) {
  g.newGame(s);
  // Как в игре: часть войска паладинов и наёмников уже стоит в мире (ополченцы города, наёмники Онара)
  const int city = siteDefByKey("city"), farm = siteDefByKey("bigfarm");
  for(int i=0; i<8; ++i) {
    auto* u = g.adoptUnit(F_Paladins,int16_t(unitDefByKey(i<5 ? "mil_recruit" : "mil_veteran")),
                          siteDef(size_t(city)).approx+V3(float(i*150),0,0));
    w.spawnUnit(*u,g.roll(u->def),u->pos);
    }
  for(int i=0; i<6; ++i) {
    auto* u = g.adoptUnit(F_Mercs,int16_t(unitDefByKey(i<4 ? "sld_recruit" : "sld_merc")),
                          siteDef(size_t(farm)).approx+V3(float(i*150),0,0));
    w.spawnUnit(*u,g.roll(u->def),u->pos);
    }
  for(const char* key : {"hagen","lothar","andre","lee","torlof","sylvio"}) {
    const int h = heroDefByKey(key);
    auto& d = heroDef(size_t(h));
    const V3 at = siteDef(size_t(d.faction==F_Paladins ? city : farm)).approx;
    auto* u = g.adoptHero(int16_t(h),at);
    w.spawnHero(*u,d,g.state().heroes.back(),at);
    }
  g.startArmies();
  // Отряды гарнизона для принятых бойцов
  for(auto& u:g.state().units)
    if(u.squad==0) {
      const int32_t t = g.capital(u.f);
      if(t>=0)
        g.joinSquad(u,g.garrison(u.f,t));
      }
  }

static void run(Game& g, Headless& w, uint64_t ms) {
  const uint64_t step = 100;
  for(uint64_t t=0; t<ms; t+=step) {
    w.step(step);
    g.tick(step);
    }
  }

struct Summary {
  Faction  winner = F_None;
  size_t   towns[F_Count] = {};
  size_t   sites[F_Count] = {};
  size_t   buildings[F_Count] = {};
  size_t   units[F_Count] = {};
  int      maxHero[F_Count] = {};
  bool     truce[3] = {};    // пары: 0 - пал/нао, 1 - пал/орк, 2 - нао/орк
  bool     alliance[3] = {};
  int      captures = 0;
  int      newTowns = 0;
  bool     ok = true;
  };

static int pairIdx(Faction a, Faction b) {
  if(a>b) std::swap(a,b);
  if(a==F_Paladins && b==F_Mercs) return 0;
  if(a==F_Paladins && b==F_Orcs)  return 1;
  return 2;
  }

static Summary play(uint32_t seed, uint64_t minutes, bool verbose) {
  Headless w;
  w.verbose = verbose;
  Game g(w);
  w.game = &g;
  Setup s;
  s.mode = Mode::Observer;
  s.seed = seed;
  setupGame(g,w,s);
  Summary sum;
  for(uint64_t m=0; m<minutes; ++m) {
    run(g,w,60000);
    if(verbose && (m%5)==4) {
      std::printf("  -- %d мин:", int(m+1));
      for(uint8_t i=0; i<F_Count; ++i) {
        auto& f = g.state().fac[i];
        std::printf("  %s %.0f/%.0f %d/%d +%.0f/%.0f", factionName(Faction(i)), double(f.gold), double(f.food), int(f.supply), int(f.cap),
                    double(g.incomeGold(Faction(i))-g.upkeepGold(Faction(i))), double(g.incomeFood(Faction(i))-g.upkeepFood(Faction(i))));
        }
      std::printf("\n");
      }
    for(uint8_t i=0; i<F_Count; ++i)
      for(uint8_t k=i+1; k<F_Count; ++k) {
        auto t = g.state().fac[i].rel[k].t;
        if(t==Treaty::Truce)    sum.truce[pairIdx(Faction(i),Faction(k))] = true;
        if(t==Treaty::Alliance) sum.alliance[pairIdx(Faction(i),Faction(k))] = true;
        }
    for(uint8_t i=0; i<F_Count; ++i) {
      auto& f = g.state().fac[i];
      if(!std::isfinite(f.gold) || !std::isfinite(f.food) || f.gold<0 || f.food<0)
        sum.ok = false;
      }
    if(g.state().winner!=F_None)
      break;
    }
  auto& st = g.state();
  sum.winner = st.winner;
  for(auto& t:st.towns)
    if(t.alive && t.f<F_Count)
      sum.towns[t.f]++;
  for(auto& s2:st.sites)
    if(s2.owner<F_Count)
      sum.sites[s2.owner]++;
  for(auto& b:st.buildings)
    if(b.f<F_Count && !b.ruined && !b.builtin)
      sum.buildings[b.f]++;
  for(auto& u:st.units)
    if(u.f<F_Count && u.st==UnitState::Alive)
      sum.units[u.f]++;
  for(auto& h:st.heroes)
    sum.maxHero[h.f] = std::max(sum.maxHero[h.f],int(h.level));
  for(auto& e:w.log) {
    if(e.text.find("теперь за стороной")!=std::string::npos)
      sum.captures++;
    if(e.text.find("закладка")!=std::string::npos)
      sum.newTowns++;
    }
  return sum;
  }

// ---- проверки ----

static void testCatalog() {
  for(size_t i=0; i<unitDefCount(); ++i) {
    auto& d = unitDef(i);
    CHECK(d.faction<F_Count, "unit %s: faction", d.key);
    CHECK(d.tmpl!=nullptr && d.tmpl[0], "unit %s: template", d.key);
    CHECK(d.gold>0 && d.trainMs>0 && d.supply>0, "unit %s: cost", d.key);
    if(d.body==Body::Human) {
      CHECK(d.armor[0]!=nullptr, "unit %s: armor", d.key);
      CHECK(d.melee[0]!=nullptr, "unit %s: weapon", d.key);
      CHECK(d.skill[1]<=90, "unit %s: skill > 90%% (только герои до 100)", d.key);
      CHECK(d.hp[0]<=d.hp[1] && d.str[0]<=d.str[1] && d.skill[0]<=d.skill[1], "unit %s: ranges", d.key);
      }
    if(d.role==Role::Ranged)
      CHECK(d.ranged[0]!=nullptr && d.ammo!=nullptr, "unit %s: ranged without bow", d.key);
    }
  // Ранги: у каждой стороны есть новобранцы и лучшие; лучшие сильнее и дороже
  for(uint8_t f=0; f<F_Count; ++f) {
    float p0 = 1e9f, p3 = 0;
    int g0 = 0, g3 = 0;
    for(size_t i=0; i<unitDefCount(); ++i) {
      auto& d = unitDef(i);
      if(d.faction!=Faction(f))
        continue;
      if(d.tier==0) { p0 = std::min(p0,d.power); g0 = d.gold; }
      if(d.tier==3) { p3 = std::max(p3,d.power); g3 = d.gold; }
      }
    CHECK(p3>p0*3.f && g3>g0*3, "faction %d: tiers", int(f));
    }
  // У каждой стороны три героя
  int heroes[F_Count] = {};
  for(size_t i=0; i<heroDefCount(); ++i)
    heroes[heroDef(i).faction]++;
  for(uint8_t f=0; f<F_Count; ++f)
    CHECK(heroes[f]==3, "faction %d: %d heroes", int(f), heroes[f]);
  CHECK(buildingDef(B_TownHall).gold>=5*buildingDef(B_Barracks).gold, "town hall: must be expensive");
  CHECK(buildingDef(B_TownHall).buildMs>=4*buildingDef(B_Barracks).buildMs, "town hall: must take long");
  }

static void testGenerator() {
  Headless w;
  Game g(w);
  w.game = &g;
  Setup s;
  s.seed = 5;
  g.newGame(s);
  const int16_t rec = int16_t(unitDefByKey("sld_recruit"));
  const int16_t vet = int16_t(unitDefByKey("sld_veteran"));
  std::set<std::string> weapons, heads;
  std::set<int> faces;
  int rangedN = 0;
  float skRec = 0, skVet = 0;
  for(int i=0; i<200; ++i) {
    auto a = g.roll(rec);
    auto b = g.roll(vet);
    CHECK(a.armor=="ITAR_SLD_L", "recruit armor %s", a.armor.c_str());
    CHECK(b.armor=="ITAR_SLD_H", "veteran armor %s", b.armor.c_str());
    weapons.insert(a.rangedMain ? a.ranged : a.melee);
    heads.insert(a.head);
    faces.insert(a.face);
    if(a.rangedMain) {
      ++rangedN;
      CHECK(a.ammoCount>0 && !a.ammo.empty(), "archer without arrows");
      }
    skRec += float(std::max(a.skill1h,a.skill2h));
    skVet += float(std::max(b.skill1h,b.skill2h));
    CHECK(std::max(b.skill1h,b.skill2h)<=90, "veteran skill %d", std::max(b.skill1h,b.skill2h));
    CHECK(a.hp>=unitDef(size_t(rec)).hp[0] && a.hp<=unitDef(size_t(rec)).hp[1], "hp range");
    }
  CHECK(weapons.size()>=5, "recruits: only %zu weapons", weapons.size());
  CHECK(heads.size()>=5 && faces.size()>=20, "faces: %zu heads, %zu faces", heads.size(), faces.size());
  CHECK(rangedN>20 && rangedN<90, "recruits with bows: %d of 200 (ждали ~25%%)", rangedN);
  CHECK(skVet>skRec*1.8f, "veterans must fight better: %f vs %f", double(skVet), double(skRec));
  // Герой: до 100 %
  auto h = g.rollHero(int16_t(heroDefByKey("lee")),HeroMaxLevel);
  CHECK(h.skill2h==100, "hero max skill %d", h.skill2h);
  auto h1 = g.rollHero(int16_t(heroDefByKey("lee")),1);
  CHECK(h1.hp<h.hp && h1.str<h.str, "hero grows with level");
  }

static void testEconomyBuild() {
  Headless w;
  Game g(w);
  w.game = &g;
  Setup s;
  s.mode   = Mode::Commander;
  s.player = F_Paladins;
  s.seed   = 3;
  setupGame(g,w,s);
  auto& st = g.state();
  CHECK(!st.fac[F_Paladins].ai && st.fac[F_Mercs].ai, "player faction is not AI");
  // Остальные стороны - без ИИ: проверяется хозяйство, а не война
  st.fac[F_Mercs].ai = st.fac[F_Orcs].ai = false;
  const int32_t cap = g.capital(F_Paladins);
  CHECK(cap>=0, "paladin capital");
  // Ратушу второй раз в том же городе не поставить; без денег - нельзя
  std::string why;
  CHECK(!g.canBuild(F_Paladins,cap,B_TownHall,&why), "second town hall in a town");
  st.fac[F_Paladins].gold = 50;
  CHECK(!g.build(F_Paladins,cap,B_Farm,&why) && why.find("золота")!=std::string::npos, "build without gold: %s", why.c_str());
  st.fac[F_Paladins].gold = 5000;
  st.fac[F_Paladins].food = 2000;
  CHECK(g.build(F_Paladins,cap,B_Farm,&why), "farm: %s", why.c_str());
  CHECK(g.build(F_Paladins,cap,B_Infirmary,&why), "infirmary: %s", why.c_str());
  const size_t before = st.buildings.size();
  run(g,w,buildingDef(B_Farm).buildMs+2000);
  size_t done = 0;
  for(auto& b:st.buildings)
    if(b.f==F_Paladins && b.type==B_Farm && b.done)
      ++done;
  CHECK(done==1, "farm built: %zu", done);
  CHECK(st.buildings.size()==before, "buildings count");
  // Найм: рыцарь - в казарме с кузницей; очередь; появляется в гарнизоне
  uint32_t barracks = 0;
  for(auto& b:st.buildings)
    if(b.f==F_Paladins && b.type==B_Barracks)
      barracks = b.id;
  const size_t n0 = w.bodies.size();
  CHECK(g.train(F_Paladins,barracks,int16_t(unitDefByKey("pal_knight")),&why), "train knight: %s", why.c_str());
  CHECK(g.train(F_Paladins,barracks,int16_t(unitDefByKey("mil_recruit")),&why), "train recruit: %s", why.c_str());
  CHECK(!g.train(F_Paladins,barracks,int16_t(unitDefByKey("sld_recruit")),&why), "train enemy unit");
  run(g,w,unitDef(size_t(unitDefByKey("pal_knight"))).trainMs+unitDef(size_t(unitDefByKey("mil_recruit"))).trainMs+1500);
  CHECK(w.bodies.size()>=n0+2, "trained units appeared: %zu -> %zu", n0, w.bodies.size());
  // Второй город: дорого и долго
  const int lob = siteDefByKey("farm_lobart");
  st.sites[size_t(lob)].owner = F_Paladins;
  st.fac[F_Paladins].gold = 1000;
  CHECK(!g.foundTown(F_Paladins,lob,&why), "town hall too cheap");
  st.fac[F_Paladins].gold = 5000;
  CHECK(g.foundTown(F_Paladins,lob,&why), "found town: %s", why.c_str());
  const int32_t t2 = st.sites[size_t(lob)].town;
  CHECK(t2>=0 && !g.canBuild(F_Paladins,t2,B_Farm), "no building before the hall is done");
  run(g,w,buildingDef(B_TownHall).buildMs/2);
  CHECK(!g.hasDone(F_Paladins,t2,B_TownHall), "town hall built too fast");
  run(g,w,buildingDef(B_TownHall).buildMs/2+3000);
  CHECK(g.hasDone(F_Paladins,t2,B_TownHall), "town hall not built");
  CHECK(g.canBuild(F_Paladins,t2,B_Farm,&why), "build near new hall: %s", why.c_str());
  }

static void testHeroes() {
  Headless w;
  Game g(w);
  w.game = &g;
  Setup s;
  s.mode = Mode::Commander;
  s.seed = 9;
  setupGame(g,w,s);
  auto& st = g.state();
  // Лорд Хаген: опыт за победы, уровни
  Hero* hagen = nullptr;
  for(auto& h:st.heroes)
    if(std::strcmp(heroDef(size_t(h.def)).key,"hagen")==0)
      hagen = &h;
  CHECK(hagen!=nullptr, "Hagen");
  if(hagen==nullptr)
    return;
  const uint32_t hu = hagen->unit;
  // Убитые им орки
  int kills = 0;
  for(auto& u:st.units)
    if(u.f==F_Orcs && u.hero<0 && kills<12) {
      w.bodies[u.id].alive = false;
      g.onKill(hu,u.id);
      ++kills;
      }
  hagen = nullptr;
  for(auto& h:st.heroes)
    if(std::strcmp(heroDef(size_t(h.def)).key,"hagen")==0)
      hagen = &h;
  CHECK(hagen->level>=2, "Hagen level after %d kills: %d (xp %d)", kills, int(hagen->level), int(hagen->xp));
  const int32_t lvl = hagen->level;
  // Побеждён: без сознания, уносят лечиться, возвращается с тем же уровнем
  w.bodies[hu].down = true;
  g.onDown(hu,0);
  run(g,w,9000);
  CHECK(hagen->st==UnitState::Healing, "Hagen should heal, state %d", int(hagen->st));
  run(g,w,60000+8000*uint64_t(lvl)+2000);
  CHECK(hagen->st==UnitState::Alive && hagen->level==lvl, "Hagen back: state %d level %d", int(hagen->st), int(hagen->level));
  CHECK(w.revived>=1, "revive call");
  }

static void testDiplomacy() {
  // Паладины и орки - не мирятся; наёмники при сильных орках - мирятся с паладинами
  Headless w;
  Game g(w);
  w.game = &g;
  Setup s;
  s.mode = Mode::Commander;
  s.player = F_Paladins;
  s.seed = 11;
  setupGame(g,w,s);
  auto& st = g.state();
  auto a = g.propose(F_Paladins,F_Orcs,Proposal::Truce,0);
  CHECK(!a.accepted, "orcs accepted truce with paladins: %s", a.text.c_str());
  // Даже с данью
  st.fac[F_Paladins].gold = 5000;
  st.fac[F_Paladins].rel[F_Orcs].lastOffer = 0;
  st.now += sec(120);
  a = g.propose(F_Paladins,F_Orcs,Proposal::Truce,2000);
  CHECK(!a.accepted, "orcs bought by gold: %s", a.text.c_str());
  // Орки сильны: добавим им войско
  for(int i=0; i<30; ++i) {
    auto* u = g.adoptUnit(F_Orcs,int16_t(unitDefByKey("orc_elite")),siteDef(size_t(siteDefByKey("pass"))).approx);
    w.spawnUnit(*u,g.roll(u->def),u->pos);
    }
  st.now += sec(120);
  a = g.propose(F_Paladins,F_Mercs,Proposal::Truce,300);
  CHECK(a.accepted, "mercs refuse truce against strong orcs: %s", a.text.c_str());
  CHECK(st.fac[F_Mercs].rel[F_Paladins].t==Treaty::Truce && !g.hostile(F_Mercs,F_Paladins), "truce state");
  CHECK(w.treaties[std::make_pair(0,1)]==Treaty::Truce, "world told about truce");
  // Союз - не сразу
  a = g.propose(F_Paladins,F_Mercs,Proposal::Alliance,0);
  CHECK(!a.accepted, "alliance right after truce");
  st.now += sec(200);
  a = g.propose(F_Paladins,F_Mercs,Proposal::Alliance,0);
  CHECK(a.accepted, "alliance against orcs: %s", a.text.c_str());
  // Разрыв раньше срока - обман запоминается
  a = g.propose(F_Paladins,F_Mercs,Proposal::War,0);
  CHECK(a.accepted && g.hostile(F_Paladins,F_Mercs), "war again");
  // Дань: наёмники любят золото больше орков
  const float o0 = st.fac[F_Mercs].rel[F_Paladins].opinion;
  g.propose(F_Paladins,F_Mercs,Proposal::Tribute,500);
  const float o1 = st.fac[F_Mercs].rel[F_Paladins].opinion;
  const float q0 = st.fac[F_Orcs].rel[F_Paladins].opinion;
  g.propose(F_Paladins,F_Orcs,Proposal::Tribute,500);
  const float q1 = st.fac[F_Orcs].rel[F_Paladins].opinion;
  CHECK(o1-o0>q1-q0, "mercs value gold more: %f vs %f", double(o1-o0), double(q1-q0));
  }

static void testSaveLoad() {
  Headless w;
  Game g(w);
  w.game = &g;
  Setup s;
  s.mode = Mode::Observer;
  s.seed = 21;
  setupGame(g,w,s);
  run(g,w,sec(600));
  const std::string a = g.save();
  Headless w2;
  Game g2(w2);
  w2.game = &g2;
  CHECK(g2.load(a), "load");
  const std::string b = g2.save();
  CHECK(a==b, "save -> load -> save differs (%zu vs %zu bytes)", a.size(), b.size());
  CHECK(!g2.load("garbage"), "garbage accepted");
  // Неизвестные строки - пропускаются (будущие версии)
  std::string c = a;
  c.insert(c.find("unit "), "future 1 2 3\n");
  CHECK(g2.load(c), "unknown line breaks load");
  }

static void testFormation() {
  Headless w;
  Game g(w);
  w.game = &g;
  Setup s;
  s.mode = Mode::Commander;
  s.player = F_Mercs;
  s.seed = 4;
  setupGame(g,w,s);
  auto& st = g.state();
  // 10 бойцов наёмников - на перекрёсток строем «клин»
  std::vector<uint32_t> ids;
  for(auto& u:st.units)
    if(u.f==F_Mercs && u.hero<0 && ids.size()<10)
      ids.push_back(u.id);
  const V3 to = siteDef(size_t(siteDefByKey("bigcross"))).approx;
  const uint32_t sq = g.command(ids,Order::March,to,0,Formation::Wedge);
  CHECK(sq!=0, "command");
  run(g,w,sec(150));
  float maxD = 0, minPair = 1e9f;
  for(auto id:ids) {
    auto* u = st.unit(id);
    if(u==nullptr || u->st!=UnitState::Alive)
      continue;
    maxD = std::max(maxD,distXZ(u->pos,to));
    for(auto id2:ids)
      if(id2!=id)
        if(auto* o = st.unit(id2))
          minPair = std::min(minPair,distXZ(o->pos,u->pos));
    }
  CHECK(maxD<1500.f, "squad not at the goal: %.0f cm", double(maxD));
  CHECK(minPair>80.f, "units on top of each other: %.0f cm", double(minPair));
  auto* s2 = st.squad(sq);
  CHECK(s2!=nullptr && s2->task==Task::Player, "player squad must stay player's");
  }

static void testMarch() {
  // Ход строем: сначала сбор, потом бег без повторных приказов (раз за плечо пути и раз в 10 с - проверка строя);
  // быстрые (варги) убегают - строй собирается снова; пришли - через несколько секунд по местам
  Headless w;
  Game g(w);
  w.game = &g;
  Setup s;
  s.mode = Mode::Commander;
  s.player = F_Mercs;
  s.seed = 9;
  setupGame(g,w,s);
  auto& st = g.state();
  std::vector<uint32_t> ids;
  V3 c;
  for(auto& u:st.units)
    if(u.f==F_Mercs && u.hero<0 && u.st==UnitState::Alive && ids.size()<12) {
      ids.push_back(u.id);
      c = c+u.pos;
      }
  c = c*(1.f/float(ids.size()));
  // Двое - быстрые, как варги: строй рассыпается
  w.bodies[ids[1]].speed = 520.f;
  w.bodies[ids[2]].speed = 520.f;
  const V3 to = siteDef(size_t(siteDefByKey("bigcross"))).approx;
  const uint32_t sq = g.command(ids,Order::Move,to,0,Formation::Wedge);
  w.moveCalls.clear();
  const float ideal = distXZ(c,to)/320.f; // с: бегом напрямик
  float arrived = -1;
  int regroups = 0;
  Phase was = Phase::None;
  for(uint64_t t=0; t<sec(240); t+=100) {
    w.step(100);
    g.tick(100);
    auto* q = st.squad(sq);
    if(q==nullptr)
      break;
    if(q->phase==Phase::Form && was==Phase::March)
      ++regroups;
    was = q->phase;
    if(arrived<0 && q->order==Order::Hold)
      arrived = float(t)/1000.f;
    }
  int maxCalls = 0;
  for(auto& [id,n]:w.moveCalls)
    maxCalls = std::max(maxCalls,n);
  std::printf("march: %.0f m, ideal %.0f s, arrived %.0f s, regroups %d, max moveTo per unit %d, settled %d\n",
              double(distXZ(c,to)/100.f),double(ideal),double(arrived),regroups,maxCalls,w.settleCalls);
  CHECK(arrived>0 && arrived<ideal*1.8f+15.f, "march too slow: %.0f s (ideal %.0f s)", double(arrived), double(ideal));
  CHECK(maxCalls<=30, "units re-ordered too often: %d moveTo for one unit", maxCalls);
  CHECK(regroups>=1, "fast units ran away, the squad never regrouped");
  // Отряд игрока стоит у перекрёстка - он не их место: не расселяются. Занятое место - расселяются
  for(auto& site:st.sites)
    if(siteDef(size_t(site.def)).key==std::string("bigcross"))
      site.owner = F_Mercs;
  run(g,w,sec(15));
  int settled = 0;
  for(auto id:ids)
    if(auto* u = st.unit(id); u!=nullptr && u->settled)
      ++settled;
  CHECK(settled>=int(ids.size())-1, "squad at its own site did not settle: %d of %zu", settled, ids.size());
  // Новый приказ - встают
  const uint32_t sq2 = g.command(ids,Order::Move,c,0,Formation::Line);
  int still = 0;
  for(auto id:ids)
    if(auto* u = st.unit(id); u!=nullptr && u->settled)
      ++still;
  CHECK(sq2!=0 && still==0 && w.unsettleCalls>=settled, "settled units must stand up on a new order");
  }

static void testFactionsBuild() {
  // Паладины и наёмники строят (и когда большим домам места нет - строят меньшие), поиск места не повторяется без конца
  for(float cramped : {1e9f, 650.f}) {
    Headless w;
    Game g(w);
    w.game = &g;
    w.lotMaxRadius = cramped;
    Setup s;
    s.mode = Mode::Observer;
    s.seed = 21;
    setupGame(g,w,s);
    run(g,w,sec(900));
    size_t built[F_Count] = {};
    for(auto& b:g.state().buildings)
      if(!b.builtin && !b.ruined)
        built[b.f]++;
    std::printf("build (lots up to %.0f cm): paladins %zu, mercs %zu, orcs %zu, failed lot searches %d\n",
                double(std::min(cramped,99999.f)),built[F_Paladins],built[F_Mercs],built[F_Orcs],w.lotFails);
    CHECK(built[F_Paladins]>=2 && built[F_Mercs]>=2, "paladins %zu / mercs %zu buildings in 15 min", built[F_Paladins], built[F_Mercs]);
    CHECK(w.lotFails<=60, "lot search repeated too often: %d", w.lotFails);
    }
  }

static void testAiPlays() {
  // Три ИИ: строят, нанимают, нападают, захватывают; деньги не уходят в минус и бесконечность
  Summary s = play(77,40,false);
  CHECK(s.ok, "resources invalid");
  size_t built = 0;
  for(uint8_t f=0; f<F_Count; ++f)
    built += s.buildings[f];
  CHECK(built>=6, "AI builds too little: %zu", built);
  CHECK(s.captures>=2, "no captures in 40 minutes: %d", s.captures);
  }

static void sweep(int n, uint64_t minutes) {
  int wins[F_Count+1] = {};
  int truce[3] = {}, alli[3] = {};
  double towns[F_Count] = {}, sites[F_Count] = {}, hero[F_Count] = {}, units[F_Count] = {};
  int captures = 0, newTowns = 0, bad = 0;
  for(int i=0; i<n; ++i) {
    auto s = play(uint32_t(1000+i),minutes,false);
    wins[s.winner<F_Count ? s.winner : F_Count]++;
    for(int k=0; k<3; ++k) {
      truce[k] += s.truce[k];
      alli[k]  += s.alliance[k];
      }
    for(uint8_t f=0; f<F_Count; ++f) {
      towns[f] += double(s.towns[f]);
      sites[f] += double(s.sites[f]);
      hero[f]  += s.maxHero[f];
      units[f] += double(s.units[f]);
      }
    captures += s.captures;
    newTowns += s.newTowns;
    bad += !s.ok;
    }
  std::printf("Партий: %d по %d мин\n",n,int(minutes));
  for(uint8_t f=0; f<F_Count; ++f)
    std::printf("  %-9s побед %3d  городов %.2f  мест %.2f  бойцов %.1f  герой ур. %.1f\n",factionName(Faction(f)),
                wins[f],towns[f]/n,sites[f]/n,units[f]/n,hero[f]/n);
  std::printf("  без победителя: %d\n",wins[F_Count]);
  std::printf("  перемирие: пал/нао %d, пал/орк %d, нао/орк %d; союз: %d, %d, %d\n",truce[0],truce[1],truce[2],alli[0],alli[1],alli[2]);
  std::printf("  захватов мест: %.1f на партию, новых городов: %.2f, ошибок ресурсов: %d\n",double(captures)/n,double(newTowns)/n,bad);
  }

int main(int argc, char** argv) {
  if(argc>=2 && std::strcmp(argv[1],"sweep")==0) {
    sweep(argc>=3 ? std::atoi(argv[2]) : 30, argc>=4 ? uint64_t(std::atoi(argv[3])) : 60);
    return 0;
    }
  if(argc>=3 && std::strcmp(argv[1],"game")==0) {
    auto s = play(uint32_t(std::atoi(argv[2])),argc>=4 ? uint64_t(std::atoi(argv[3])) : 60,true);
    std::printf("Победитель: %s\n",s.winner<F_Count ? factionName(s.winner) : "нет");
    return 0;
    }
  testCatalog();
  testGenerator();
  testEconomyBuild();
  testHeroes();
  testDiplomacy();
  testSaveLoad();
  testFormation();
  testMarch();
  testFactionsBuild();
  testAiPlays();
  if(fails==0)
    std::printf("ALL OK\n");
  else
    std::printf("%d FAILED\n",fails);
  return fails==0 ? 0 : 1;
  }
