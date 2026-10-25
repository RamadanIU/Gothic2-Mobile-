// Gothic II Mobile: стенд регдолла (tools/ragdoll-test/build.sh).
// Заглушки для проверки регдолла на компьютере: настоящие ragdoll.cpp и Bullet, упрощённые мир и скелет.
#include "physics/collisionworld.h"
#include "physics/dynamicworld.h"
#include "physics/ragdoll.h"
#include "graphics/mesh/skeleton.h"

#include <Tempest/Log>
#include <BulletCollision/CollisionShapes/btStaticPlaneShape.h>
#include <algorithm>

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
           cat==DynamicWorld::C_Item      || cat==DynamicWorld::C_Ragdoll;
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
  if(ragBodies.size()>0)
    this->stepSimulation(float(dt)/1000.f, 4);
  }

// --- DynamicWorld: земля - плоскость, своя очередь кукол (копия RagdollList)
struct DynamicWorld::RagdollList final {
  std::vector<Ragdoll*> list;
  };

std::unique_ptr<btCollisionShape> groundShape;
std::unique_ptr<btRigidBody>      groundBody;

DynamicWorld::DynamicWorld(World*, const zenkit::Mesh&) {
  world.reset(new CollisionWorld());
  ragdolls.reset(new RagdollList());
  world->setInternalTickCallback(&DynamicWorld::ragdollPreTick, this, true);

  groundShape.reset(new btStaticPlaneShape(btVector3(0,1,0),0));
  btRigidBody::btRigidBodyConstructionInfo ci(0,nullptr,groundShape.get());
  ci.m_friction = 0.75f;
  groundBody.reset(new btRigidBody(ci));
  groundBody->setUserIndex(C_Landscape);
  world->addCollisionObject(groundBody.get());
  }

DynamicWorld::~DynamicWorld() {
  world->removeCollisionObject(groundBody.get());
  }

void DynamicWorld::addRagdoll(Ragdoll* r) { ragdolls->list.push_back(r); }
void DynamicWorld::delRagdoll(Ragdoll* r) {
  auto& l = ragdolls->list;
  l.erase(std::remove(l.begin(),l.end(),r),l.end());
  }

void DynamicWorld::ragdollPreTick(btDynamicsWorld* w, float dt) {
  auto self = reinterpret_cast<DynamicWorld*>(w->getWorldUserInfo());
  for(auto r:self->ragdolls->list)
    r->preStep(dt);
  }

void DynamicWorld::tick(uint64_t dt) {
  world->tick(dt);
  for(auto r:ragdolls->list) {
    if(!r->isSimulated())
      continue;
    r->afterStep(dt);
    if(r->calmTime>700 || r->simTime>12000)
      r->freeze();
    }
  }

float waterLevel = -1e9f;
DynamicWorld::RayWaterResult DynamicWorld::waterRay(const Tempest::Vec3& from, float) const {
  RayWaterResult r;
  r.hasCol = waterLevel>from.y;
  r.wdepth = waterLevel;
  return r;
  }
