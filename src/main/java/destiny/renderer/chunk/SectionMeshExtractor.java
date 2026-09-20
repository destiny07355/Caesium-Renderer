package destiny.renderer.chunk;

import caesium.engine.world.RenderWorld;
import net.minecraft.util.math.ChunkSectionPos;

public final class SectionMeshExtractor{
private SectionMeshExtractor(){}
private static final float[] FACE_LIGHT={0.80f,0.80f,0.65f,0.65f,1.00f,0.50f,};
private static final int[][][] FACE_CORNERS={
{{0,0,1},{1,0,1},{1,1,1},{0,1,1}},
{{1,0,0},{0,0,0},{0,1,0},{1,1,0}},
{{1,0,1},{1,0,0},{1,1,0},{1,1,1}},
{{0,0,0},{0,0,1},{0,1,1},{0,1,0}},
{{0,1,1},{1,1,1},{1,1,0},{0,1,0}},
{{0,0,0},{1,0,0},{1,0,1},{0,0,1}},
};
private static final int[][] FACE_NORMAL={{0,0,1},{0,0,-1},{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},};

public static RenderWorld.SectionMesh extract(ChunkSectionPos pos,ChunkSectionData data,int revision){
int faceCount=countVisibleFaces(data);
if(faceCount==0)return null;
float[] positions=new float[faceCount*4*3];
float[] colors=new float[faceCount*4*4];
int[] indices=new int[faceCount*6];
int vertCount=0;
int idxCount=0;
int ox=data.originX;
int oy=data.originY;
int oz=data.originZ;
for(int ly=1;ly<=16;ly++){
for(int lz=1;lz<=16;lz++){
for(int lx=1;lx<=16;lx++){
int stateId=data.getStateId(lx,ly,lz);
if(stateId==0||BlockStateLUT.isEmpty(stateId))continue;
int color=BlockStateLUT.colorOf(stateId);
float cr=((color>>>16)&0xFF)/255.0f;
float cg=((color>>>8)&0xFF)/255.0f;
float cb=(color&0xFF)/255.0f;
int bx=lx-1;
int by=ly-1;
int bz=lz-1;
for(int face=0;face<6;face++){
int[] n=FACE_NORMAL[face];
int nlx=lx+n[0],nly=ly+n[1],nlz=lz+n[2];
if(data.isOpaque(nlx,nly,nlz))continue;
float light=FACE_LIGHT[face];
int[] corner0=FACE_CORNERS[face][0];
int[] corner1=FACE_CORNERS[face][1];
int[] corner2=FACE_CORNERS[face][2];
int[] corner3=FACE_CORNERS[face][3];
emit(positions,colors,vertCount,ox+bx+corner0[0],oy+by+corner0[1],oz+bz+corner0[2],ox+bx+corner1[0],oy+by+corner1[1],oz+bz+corner1[2],ox+bx+corner2[0],oy+by+corner2[1],oz+bz+corner2[2],ox+bx+corner3[0],oy+by+corner3[1],oz+bz+corner3[2],cr*light,cg*light,cb*light);
indices[idxCount++]=vertCount;
indices[idxCount++]=vertCount+1;
indices[idxCount++]=vertCount+2;
indices[idxCount++]=vertCount;
indices[idxCount++]=vertCount+2;
indices[idxCount++]=vertCount+3;
vertCount+=4;
}
}
}
}
return new RenderWorld.SectionMesh(pos.getSectionX(),pos.getSectionZ(),pos.getSectionY(),revision,positions,colors,indices);
}

private static int countVisibleFaces(ChunkSectionData data){
int faces=0;
for(int ly=1;ly<=16;ly++){
for(int lz=1;lz<=16;lz++){
for(int lx=1;lx<=16;lx++){
int stateId=data.getStateId(lx,ly,lz);
if(stateId==0||BlockStateLUT.isEmpty(stateId))continue;
for(int face=0;face<6;face++){
int[] n=FACE_NORMAL[face];
if(!data.isOpaque(lx+n[0],ly+n[1],lz+n[2]))faces++;
}
}
}
}
return faces;
}

private static void emit(float[] positions,float[] colors,int vert,float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,float dx,float dy,float dz,float r,float g,float bl){
putVertex(positions,colors,vert,ax,ay,az,r,g,bl);
putVertex(positions,colors,vert+1,bx,by,bz,r,g,bl);
putVertex(positions,colors,vert+2,cx,cy,cz,r,g,bl);
putVertex(positions,colors,vert+3,dx,dy,dz,r,g,bl);
}

private static void putVertex(float[] positions,float[] colors,int vert,float x,float y,float z,float r,float g,float b){
int pi=vert*3;
positions[pi]=x;
positions[pi+1]=y;
positions[pi+2]=z;
int ci=vert*4;
colors[ci]=r;
colors[ci+1]=g;
colors[ci+2]=b;
colors[ci+3]=1f;
}
}
