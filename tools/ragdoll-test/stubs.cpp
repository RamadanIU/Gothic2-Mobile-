// Gothic II Mobile: стенд регдолла (tools/ragdoll-test/build.sh).
// Заглушки для проверки регдолла на компьютере: настоящие ragdoll.cpp и Bullet, упрощённые мир и скелет.
#include "physics/collisionworld.h"
#include "physics/dynamicworld.h"
#include "physics/ragdoll.h"
#include "graphics/mesh/skeleton.h"

#include <Tempest/Log>
#include <BulletCollision/CollisionShapes/btStaticPlaneShape.h>
#include <BulletCollision/CollisionShapes/btBoxShape.h>
#include <BulletCollision/CollisionShapes/btBvhTriangleMeshShape.h>
#include <BulletCollision/CollisionShapes/btTriangleMesh.h>
#include <algorithm>
#include <cmath>

class PhysicVbo {};
struct DynamicWorld::NpcBodyList {};
struct DynamicWorld::BulletsList {};
struct DynamicWorld::BBoxList {};

// --- Skeleton: поля заполняет тест
Skeleton::Skeleton(const zenkit::ModelHierarchy&, const Animation* anim, std::string_view name)
  :fileName(name), anim(anim) {}

// --- CollisionWorld: только то, что нужно куклам (копия логики из collisionworld.cpp)
struct CollisionWorld::RagdollFilter : btOverlapFilterCallback {
  static bool touches(int cat) {
    return cat==DynamicWorld::C_Landscape || cat==DynamicWorld::C_Object ||
           cat==DynamicWorld::C_Item      || cat==DynamicWorld::C_Ragdoll || cat==DynamicWorld::C_RagdollProxy;
    }
  bool needBroadphaseCollision(btBroadphaseProxy* a, btBroadphaseProxy* b) const override {
    if((a->m_collisionFilterGroup & b->m_collisionFilterMask)==0 ||
       (b->m_collisionFilterGroup & a->m_collisionFilterMask)==0)
      return false;
    auto oa = reinterpret_cast<const btCollisionObject*>(a->m_clientObject);
    auto ob = reinterpret_cast<const btCollisionObject*>(b->m_clientObject);
    if(oa->getUserIndex()==DynamicWorld::C_Ragdoll)
      return touches(ob->getUserIndex());
    if(ob->getUserIndex()==DynamicWorld::C_Ragdoll)
      return touches(oa->getUserIndex());
    return true;
    }
  };

struct CollisionWorld::ContructInfo {
  ContructInfo() {
    conf  .reset(new btDefaultCollisionConfiguration());
    disp  .reset(new btCollisionDispatcher(conf.get()));
    broad .reset(new btDbvtBroadphase());
    solver.reset(new btSequentialImpulseConstraintSolver());
    }
  std::unique_ptr<btCollisionConfiguration>            conf;
  std::unique_ptr<btCollisionDispatcher>               disp;
  std::unique_ptr<btSequentialImpulseConstraintSolver> solver;
  std::unique_ptr<btBroadphaseInterface>               broad;
  };

CollisionWorld::CollisionWorld():CollisionWorld(ContructInfo()) {}

CollisionWorld::CollisionWorld(ContructInfo ci)
  :btDiscreteDynamicsWorld(ci.disp.get(), ci.broad.get(), ci.solver.get(), ci.conf.get()) {
  disp   = std::move(ci.disp);
  broad  = std::move(ci.broad);
  solver = std::move(ci.solver);
  conf   = std::move(ci.conf);
  setForceUpdateAllAabbs(false);
  gravity = btVector3(0,-DynamicWorld::gravityMS,0);
  setGravity(gravity);
  getSolverInfo().m_numIterations = 15;
  ragFilter.reset(new RagdollFilter());
  getPairCache()->setOverlapFilterCallback(ragFilter.get());
  }

void CollisionWorld::updateAabbs() {
  for(auto b:ragBodies)
    if(b->isActive())
      updateSingleAabb(b);
  if(aabbChanged>0) {
    btDiscreteDynamicsWorld::updateAabbs();
    aabbChanged = 0;
    }
  }

void CollisionWorld::saveKinematicState(btScalar) {}

void CollisionWorld::addRagdollBody(btRigidBody* b) {
  this->addRigidBody(b, int(btBroadphaseProxy::CharacterFilter)<<1, int(btBroadphaseProxy::AllFilter));
  this->updateSingleAabb(b);
  ragBodies.push_back(b);
  }

void CollisionWorld::addRagdollProxy(btRigidBody* b) {
  // группа - своя, маска - только тела кукол: проверки движения NPC, лучи, предметы его не видят
  this->addRigidBody(b, int(btBroadphaseProxy::CharacterFilter)<<2, int(btBroadphaseProxy::CharacterFilter)<<1);
  this->updateSingleAabb(b);
  }

void CollisionWorld::removeRagdollProxy(btRigidBody* b) {
  this->removeRigidBody(b);
  }

void CollisionWorld::removeRagdollBody(btRigidBody* b) {
  this->removeRigidBody(b);
  for(size_t i=0; i<ragBodies.size(); ++i)
    if(ragBodies[i]==b) {
      ragBodies[i] = ragBodies.back();
      ragBodies.pop_back();
      break;
      }
  }

void CollisionWorld::tick(uint64_t dt) {
  // как в collisionworld.cpp
  if(ragBodies.size()>0) {
    const float t = float(dt)/1000.f;
    const int   n = std::clamp(int(std::ceil(t*60.f-0.01f)), 1, 3);
    const float h = std::min(t/float(n), 1.f/30.f);
    if(t>0.f)
      this->stepSimulation(h*float(n), n, h);
    }
  }

// --- DynamicWorld: земля - плоскость или неровная сетка треугольников (как земля мира игры), куклы в физике
// земля - своя у каждого мира (проверки тряски строят мир сцены внутри мира проверок)
struct DynamicWorld::RagdollList final {
  std::vector<Ragdoll*>             active;
  std::unique_ptr<btCollisionShape> groundShape;
  std::unique_ptr<btRigidBody>      groundBody;
  std::unique_ptr<btTriangleMesh>   groundMesh;
  std::vector<std::unique_ptr<btCollisionShape>> boxShapes;
  std::vector<std::unique_ptr<btRigidBody>>      boxBodies;
  };

bool meshGround = false;
// стены и ящики сцены (опора для руки): центр и половины размеров, см; задаёт тест до создания мира
std::vector<std::pair<Tempest::Vec3,Tempest::Vec3>> benchBoxes;

DynamicWorld::DynamicWorld(World*, const zenkit::Mesh&) {
  world.reset(new CollisionWorld());
  ragdolls.reset(new RagdollList());
  world->setInternalTickCallback(&DynamicWorld::ragdollPreTick, this, true);
  // Gothic II Mobile: куклы и тела людей для них (Ragdoll::Proxy) - что с чем сталкивается и «призраки»
  static_cast<btCollisionDispatcher*>(world->getDispatcher())->setNearCallback(&Ragdoll::nearCallback);

  auto& groundMesh  = ragdolls->groundMesh;
  auto& groundShape = ragdolls->groundShape;
  auto& groundBody  = ragdolls->groundBody;
  if(meshGround) {
    // 60x60 м, клетка 1 м, бугры до ±8 см
    groundMesh.reset(new btTriangleMesh());
    auto h = [](int x, int z) { return 0.08f*std::sin(float(x)*1.3f)*std::cos(float(z)*0.7f); };
    for(int x=-30; x<30; ++x)
      for(int z=-30; z<30; ++z) {
        btVector3 a(float(x),h(x,z),float(z)), b(float(x+1),h(x+1,z),float(z));
        btVector3 c(float(x),h(x,z+1),float(z+1)), d(float(x+1),h(x+1,z+1),float(z+1));
        groundMesh->addTriangle(a,c,b);
        groundMesh->addTriangle(b,c,d);
        }
    groundShape.reset(new btBvhTriangleMeshShape(groundMesh.get(),true));
    } else {
    groundShape.reset(new btStaticPlaneShape(btVector3(0,1,0),0));
    }
  btRigidBody::btRigidBodyConstructionInfo ci(0,nullptr,groundShape.get());
  ci.m_friction = 0.75f;
  groundBody.reset(new btRigidBody(ci));
  groundBody->setUserIndex(C_Landscape);
  world->addCollisionObject(groundBody.get());
  for(auto& bx:benchBoxes) {
    const Tempest::Vec3 c = bx.first*0.01f, h = bx.second*0.01f;
    auto shape = std::make_unique<btBoxShape>(btVector3(h.x,h.y,h.z));
    btRigidBody::btRigidBodyConstructionInfo bi(0,nullptr,shape.get());
    bi.m_friction = 0.8f;
    auto rb = std::make_unique<btRigidBody>(bi);
    btTransform tr; tr.setIdentity(); tr.setOrigin(btVector3(c.x,c.y,c.z));
    rb->setWorldTransform(tr);
    rb->setUserIndex(C_Landscape);
    world->addCollisionObject(rb.get());
    ragdolls->boxShapes.push_back(std::move(shape));
    ragdolls->boxBodies.push_back(std::move(rb));
    }
  }

DynamicWorld::~DynamicWorld() {
  world->removeCollisionObject(ragdolls->groundBody.get());
  for(auto& rb:ragdolls->boxBodies)
    world->removeCollisionObject(rb.get());
  }

// как в dynamicworld.cpp
void DynamicWorld::addRagdoll(Ragdoll* r) {
  auto& l = ragdolls->active;
  if(std::find(l.begin(),l.end(),r)==l.end())
    l.push_back(r);
  }
void DynamicWorld::delRagdoll(Ragdoll* r) {
  auto& l = ragdolls->active;
  for(size_t i=0; i<l.size(); ++i)
    if(l[i]==r) {
      l[i] = l.back();
      l.pop_back();
      return;
      }
  }

void DynamicWorld::ragdollPreTick(btDynamicsWorld* w, float dt) {
  auto self = reinterpret_cast<DynamicWorld*>(w->getWorldUserInfo());
  Ragdoll::preStepAll(self->ragdolls->active, dt);
  }

auto DynamicWorld::ragdollsActive() -> std::vector<Ragdoll*>& {
  return ragdolls->active;
  }

size_t DynamicWorld::ragdollsSimulated() const {
  return ragdolls->active.size();
  }

void DynamicWorld::tick(uint64_t dt) {
  world->tick(dt);
  Ragdoll::tickAll(ragdolls->active, dt, -1e9f);
  }

DynamicWorld::RayLandResult DynamicWorld::ray(const Tempest::Vec3& from, const Tempest::Vec3& to) const {
  // земля стенда: ближайшее попадание луча (тела кукол лучи не видят - как в игре)
  struct CB : btCollisionWorld::ClosestRayResultCallback {
    using ClosestRayResultCallback::ClosestRayResultCallback;
    bool needsCollision(btBroadphaseProxy* p) const override {
      auto o = reinterpret_cast<const btCollisionObject*>(p->m_clientObject);
      return o->getUserIndex()==C_Landscape;
      }
    };
  const btVector3 a(from.x*0.01f,from.y*0.01f,from.z*0.01f), b(to.x*0.01f,to.y*0.01f,to.z*0.01f);
  CB cb(a,b);
  world->rayTest(a,b,cb);
  RayLandResult r;
  if(cb.hasHit()) {
    r.hasCol = true;
    r.v      = Tempest::Vec3(cb.m_hitPointWorld.x(),cb.m_hitPointWorld.y(),cb.m_hitPointWorld.z())*100.f;
    r.n      = Tempest::Vec3(cb.m_hitNormalWorld.x(),cb.m_hitNormalWorld.y(),cb.m_hitNormalWorld.z());
    r.hitFraction = cb.m_closestHitFraction;
    }
  return r;
  }

float waterLevel = -1e9f;
DynamicWorld::RayWaterResult DynamicWorld::waterRay(const Tempest::Vec3& from, float) const {
  RayWaterResult r;
  r.hasCol = waterLevel>from.y;
  r.wdepth = waterLevel;
  return r;
  }
