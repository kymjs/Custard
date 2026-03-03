#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA

#include "Saba/Model/MMD/MMDPhysics.h"

class btRigidBody {};
class btCollisionShape {};
class btTypedConstraint {};
class btDiscreteDynamicsWorld {};
class btBroadphaseInterface {};
class btDefaultCollisionConfiguration {};
class btCollisionDispatcher {};
class btSequentialImpulseConstraintSolver {};
class btMotionState {};
struct btOverlapFilterCallback {};

namespace saba {

class MMDMotionState {};

MMDRigidBody::MMDRigidBody()
    : m_rigidBodyType(RigidBodyType::Kinematic),
      m_group(0),
      m_groupMask(0),
      m_node(nullptr),
      m_offsetMat(1.0f) {
}

MMDRigidBody::~MMDRigidBody() {
    Destroy();
}

bool MMDRigidBody::Create(const PMDRigidBodyExt&, MMDModel*, MMDNode* node) {
    m_node = node;
    return true;
}

bool MMDRigidBody::Create(const PMXRigidbody&, MMDModel*, MMDNode* node) {
    m_node = node;
    return true;
}

void MMDRigidBody::Destroy() {
    m_shape.reset();
    m_activeMotionState.reset();
    m_kinematicMotionState.reset();
    m_rigidBody.reset();
}

btRigidBody* MMDRigidBody::GetRigidBody() const {
    return nullptr;
}

uint16_t MMDRigidBody::GetGroup() const {
    return m_group;
}

uint16_t MMDRigidBody::GetGroupMask() const {
    return m_groupMask;
}

void MMDRigidBody::SetActivation(bool) {
}

void MMDRigidBody::ResetTransform() {
}

void MMDRigidBody::Reset(MMDPhysics*) {
}

void MMDRigidBody::ReflectGlobalTransform() {
}

void MMDRigidBody::CalcLocalTransform() {
}

glm::mat4 MMDRigidBody::GetTransform() {
    return glm::mat4(1.0f);
}

MMDJoint::MMDJoint() {
}

MMDJoint::~MMDJoint() {
    Destroy();
}

bool MMDJoint::CreateJoint(const PMDJointExt&, MMDRigidBody*, MMDRigidBody*) {
    return true;
}

bool MMDJoint::CreateJoint(const PMXJoint&, MMDRigidBody*, MMDRigidBody*) {
    return true;
}

void MMDJoint::Destroy() {
    m_constraint.reset();
}

btTypedConstraint* MMDJoint::GetConstraint() const {
    return nullptr;
}

MMDPhysics::MMDPhysics()
    : m_fps(120.0),
      m_maxSubStepCount(10) {
}

MMDPhysics::~MMDPhysics() {
    Destroy();
}

bool MMDPhysics::Create() {
    return true;
}

void MMDPhysics::Destroy() {
    m_broadphase.reset();
    m_collisionConfig.reset();
    m_dispatcher.reset();
    m_solver.reset();
    m_world.reset();
    m_groundShape.reset();
    m_groundMS.reset();
    m_groundRB.reset();
    m_filterCB.reset();
}

void MMDPhysics::SetFPS(float fps) {
    m_fps = fps;
}

float MMDPhysics::GetFPS() const {
    return static_cast<float>(m_fps);
}

void MMDPhysics::SetMaxSubStepCount(int numSteps) {
    m_maxSubStepCount = numSteps;
}

int MMDPhysics::GetMaxSubStepCount() const {
    return m_maxSubStepCount;
}

void MMDPhysics::Update(float) {
}

void MMDPhysics::AddRigidBody(MMDRigidBody*) {
}

void MMDPhysics::RemoveRigidBody(MMDRigidBody*) {
}

void MMDPhysics::AddJoint(MMDJoint*) {
}

void MMDPhysics::RemoveJoint(MMDJoint*) {
}

btDiscreteDynamicsWorld* MMDPhysics::GetDynamicsWorld() const {
    return nullptr;
}

} // namespace saba

#endif
